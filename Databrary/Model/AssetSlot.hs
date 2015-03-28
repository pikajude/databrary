{-# LANGUAGE OverloadedStrings, TemplateHaskell, QuasiQuotes, RecordWildCards #-}
module Databrary.Model.AssetSlot
  ( module Databrary.Model.AssetSlot.Types
  , lookupAssetSlot
  , lookupAssetAssetSlot
  , lookupSlotAssets
  , lookupContainerAssets
  , changeAssetSlot
  , findAssetContainerEnd
  , auditAssetSlotDownload
  , assetSlotJSON
  ) where

import Control.Applicative ((<*>))
import Control.Monad (when, liftM2)
import Data.Maybe (catMaybes, fromMaybe, isNothing)
import Database.PostgreSQL.Typed (pgSQL)

import Databrary.Ops
import Databrary.Has (peek, view)
import qualified Databrary.JSON as JSON
import Databrary.DB
import Databrary.Model.Offset
import Databrary.Model.Segment
import Databrary.Model.Id
import Databrary.Model.Permission
import Databrary.Model.Party.Types
import Databrary.Model.Identity.Types
import Databrary.Model.Volume.Types
import Databrary.Model.Container.Types
import Databrary.Model.Slot.Types
import Databrary.Model.Format.Types
import Databrary.Model.Asset
import Databrary.Model.Audit
import Databrary.Model.SQL
import Databrary.Model.AssetSlot.Types
import Databrary.Model.AssetSlot.SQL

lookupAssetSlot :: (MonadHasIdentity c m, DBM m) => Id Asset -> m (Maybe AssetSlot)
lookupAssetSlot ai = do
  ident <- peek
  dbQuery1 $(selectQuery (selectAssetSlot 'ident) "$WHERE asset.id = ${ai}")

lookupAssetAssetSlot :: (DBM m) => Asset -> m AssetSlot
lookupAssetAssetSlot a = fromMaybe assetNoSlot
  <$> dbQuery1 $(selectQuery selectAssetSlotAsset "$WHERE slot_asset.asset = ${assetId a}")
  <*> return a

lookupSlotAssets :: (DBM m) => Slot -> m [AssetSlot]
lookupSlotAssets (Slot c s) =
  dbQuery $ ($ c) <$> $(selectQuery selectContainerSlotAsset "$WHERE slot_asset.container = ${containerId c} AND slot_asset.segment && ${s}")

lookupContainerAssets :: (DBM m) => Container -> m [AssetSlot]
lookupContainerAssets = lookupSlotAssets . containerSlot

changeAssetSlot :: (MonadAudit c m) => AssetSlot -> m Bool
changeAssetSlot as = do
  ident <- getAuditIdentity
  if isNothing (assetSlot as)
    then dbExecute1 $(deleteSlotAsset 'ident 'as)
    else do
      (r, _) <- updateOrInsert
        $(updateSlotAsset 'ident 'as)
        $(insertSlotAsset 'ident 'as)
      when (r /= 1) $ fail $ "changeAssetSlot: " ++ show r ++ " rows"
      return True

findAssetContainerEnd :: DBM m => Container -> m (Maybe Offset)
findAssetContainerEnd c = 
  dbQuery1' [pgSQL|SELECT max(upper(segment)) FROM slot_asset WHERE container = ${containerId c}|]

auditAssetSlotDownload :: MonadAudit c m => Bool -> AssetSlot -> m ()
auditAssetSlotDownload success AssetSlot{ slotAsset = a, assetSlot = as } = do
  ai <- getAuditIdentity
  maybe
    (dbExecute1' [pgSQL|INSERT INTO audit.asset (audit_action, audit_user, audit_ip, id, volume, format, classification) VALUES
      (${act}, ${auditWho ai}, ${auditIp ai}, ${assetId a}, ${view a :: Id Volume}, ${view a :: Id Format}, ${assetClassification a :: Classification})|])
    (\s -> dbExecute1' [pgSQL|INSERT INTO audit.slot_asset (audit_action, audit_user, audit_ip, container, segment, asset) VALUES
      (${act}, ${auditWho ai}, ${auditIp ai}, ${view s :: Id Container}, ${view s :: Segment}, ${assetId a})|])
    as
  where act | success = AuditActionOpen 
            | otherwise = AuditActionAttempt

assetSlotJSON :: AssetSlot -> JSON.Object
assetSlotJSON as@AssetSlot{..} = assetJSON slotAsset JSON..++ catMaybes
  [ ("container" JSON..=) . containerId . slotContainer <$> assetSlot
  , liftM2 (?!>) segmentFull ("segment" JSON..=) =<< slotSegment <$> assetSlot
  , Just $ "permission" JSON..= (view as :: Permission)
  , ("excerpt" JSON..=) <$> assetSlotExcerpt
  ]