{-# LANGUAGE OverloadedStrings, RecordWildCards #-}
module Databrary.View.Notification
  ( mailNotifications
  , htmlNotification
  ) where

import Control.Arrow (second)
import qualified Data.ByteString.Builder as BSB
import qualified Data.ByteString.Char8 as BSC
import Data.Function (on)
import Data.Maybe (fromMaybe)
import Data.Monoid ((<>))
import qualified Data.Text.Lazy as TL
import qualified Data.Text.Lazy.Encoding as TLE
import qualified Text.Blaze.Html5 as H

import Databrary.Ops
import Databrary.Model.Permission
import Databrary.Model.Id.Types
import Databrary.Model.Party
import Databrary.Model.Volume.Types
import Databrary.Model.Segment
import Databrary.Model.Slot.Types
import Databrary.Model.Notification
import Databrary.Service.Messages
import Databrary.HTTP.Route
import Databrary.Action.Route
import Databrary.Controller.Paths
import {-# SOURCE #-} Databrary.Controller.Party
import {-# SOURCE #-} Databrary.Controller.Volume
import {-# SOURCE #-} Databrary.Controller.Slot
import {-# SOURCE #-} Databrary.Controller.AssetSegment
import Databrary.View.Authorize (authorizeSiteTitle)
import Databrary.View.Party (htmlPartyViewLink)
import Databrary.View.Volume (htmlVolumeViewLink)
import Databrary.View.VolumeAccess (volumeAccessTitle, volumeAccessPresetTitle)
import Databrary.View.Container (releaseTitle)
import Databrary.View.Html

mailLink :: Route r a -> a -> [(BSC.ByteString, BSC.ByteString)] -> TL.Text
mailLink u a q = TLE.decodeLatin1 $ BSB.toLazyByteString $ actionURL Nothing u a (map (second Just) q :: Query)

partyEditLink :: (ActionRoute PartyTarget -> PartyTarget -> t) -> PartyRow -> PartyRow -> t
partyEditLink link target p = link viewPartyEdit (if on (==) partyId p target then TargetProfile else TargetParty (partyId p))

mailNotification :: Messages -> Notification -> TL.Text
mailNotification msg Notification{..} = case notificationNotice of
  NoticeAccountChange ->
    maybe "Your" (<> "'s") partyp <> " account information has been changed. To review or update your information, go to: "
    <> partyEdit (fromMaybe target notificationParty) [("page", "account")]
    <> "\nIf you did not make this change, please contact us immediately."
  NoticeAuthorizeGranted
    | Just p <- notificationPermission ->
      "You have been authorized by " <> party <> ", as a Databrary " <> TL.fromStrict (authorizeSiteTitle p msg) <> "."
      <> if p < PermissionSHARED then mempty else
      " Your authorization allows you to access all the shared data in Databrary. \
      \Our primary goal is to inspire you to reuse shared videos on Databrary to ask new questions outside the scope of the original study. \
      \You will also find illustrative video excerpts that you can use for teaching and to learn about researchers' methods and procedures.\
      \\n\n\
      \Databrary's unique \"active curation\" functionality allows you to upload your videos as you collect them so that your data are backed up and preserved in our free, secure library, your videos are immediately available to you and your collaborators offsite, and your data are organized and ready for sharing. \
      \Your data will remain private and accessible only to your lab members and collaborators until you are ready to share with the Databrary community. \
      \When you are ready, sharing is as easy as clicking a button!\
      \\n\n\
      \To share your data, you can use our template Databrary release form for obtaining permission for sharing from your participants, which can be found here: http://databrary.org/access/policies/release-template.html\n\
      \The release form can be added to new or existing IRB protocols. \
      \It is completely adaptable and can be customized to suit your needs. \
      \We also have lots of information and helpful tips about managing and sharing your video data in our User Guide: http://databrary.org/access/guide\n\
      \As soon as your protocol is amended to allow you to share data, you can start uploading your data from each new session. \
      \Don't wait until your study is complete to upload your videos. \
      \It's much easier to upload data after each data collection while your study is in progress!\
      \\n\n\
      \We are dedicated to providing assistance to the Databrary community. \
      \Please contact us at support@databrary.org with questions or for help getting started.\
      \\n"
    | otherwise ->
      "Your authorization under " <> party <> " has been revoked. To review and apply for authorizations, go to: "
      <> partyEdit target [("page", "apply")]
  NoticeAuthorizeChildRequest ->
    party <> " has requested to be authorized. To approve or reject this authorization request, go to: "
      <> partyEdit target [("page", "grant"), partyq]
  where
  target = partyRow (accountParty notificationTarget)
  person p = on (/=) partyId p target ?> TL.fromStrict (partyName p)
  partyp = person =<< notificationParty
  party = fromMaybe "you" partyp
  partyq = ("party", maybe "" (BSC.pack . show . partyId) notificationParty)
  partyEdit = partyEditLink mailLink target

mailNotifications :: Messages -> [Notification] -> TL.Text
mailNotifications msg ~l@(Notification{ notificationTarget = u }:_) =
  TL.fromChunks ["Dear ", partyName target, ",\n"]
  <> foldMap (\n -> '\n' `TL.cons` mailNotification msg n `TL.snoc` '\n') l
  <> "\nYou can change your notification settings or unsubscribe here: "
  <> partyEditLink mailLink target target [("page", "notifications")] `TL.snoc` '\n'
  where
  target = partyRow (accountParty u)

htmlNotification :: Messages -> Notification -> H.Html
htmlNotification msg Notification{..} = case notificationNotice of
  NoticeAccountChange ->
    agent >> " changed " >> partys >> " "
    >> partyEdit (fromMaybe target notificationParty) [("page", "account")] "account information" >> "."
  NoticeAuthorizeRequest ->
    agent >> " requested "
    >> partyEdit target [("page", "apply"), partyq] "authorization" >> " from " >> party >> "."
  NoticeAuthorizeGranted ->
    agent >> " " >> granted >> " your "
    >> partyEdit target [("page", "apply"), partyq] "authorization" >> " under " >> party >> "."
  NoticeAuthorizeChildRequest ->
    agent >> " requested "
    >> partyEdit target [("page", "grant"), partyq] "authorization" >> " for " >> party >> "."
  NoticeAuthorizeChildGranted ->
    agent >> " " >> granted >> " "
    >> partyEdit target [("page", "grant"), partyq] "authorization" >> " to " >> party >> "."
  NoticeVolumeAssist ->
    agent >> " requested " >> volumeEdit [("page", "assist")] "assistance" >> " with " >> volume >> "."
  NoticeVolumeCreated ->
    agent >> " created " >> volume >> " on " >> partys >> " behalf."
  NoticeVolumeSharing ->
    agent >> " changed " >> volume >> " to "
    >> H.text (volumeAccessPresetTitle (PermissionNONE < perm) msg) >> "."
  NoticeVolumeAccessOther ->
    agent >> " " >> volumeEdit [("page", "access"), partyq] "set" >> " " >> partys
    >> " access to " >> H.text (volumeAccessTitle perm msg) >> " on " >> volume >> "."
  NoticeVolumeAccess ->
    agent >> " " >> volumeEdit [("page", "access")] "set" >> " " >> partys
    >> " access to " >> H.text (volumeAccessTitle perm msg) >> " on " >> volume >> "."
  NoticeReleaseSlot ->
    agent >> " set a " >> link viewSlot (HTML, (volumeId <$> notificationVolume, Id $ SlotId (fromMaybe noId notificationContainerId) segment)) [] "folder"
    >> " in " >> volume >> " to " >> H.text (releaseTitle notificationRelease msg) >> "."
  NoticeReleaseAsset ->
    agent >> " set a " >> link viewAssetSegment (HTML, volumeId <$> notificationVolume, Id $ SlotId (fromMaybe noId notificationContainerId) segment, fromMaybe noId notificationAssetId) [] "file"
    >> " in " >> volume >> " to " >> H.text (releaseTitle notificationRelease msg) >> "."
  NoticeReleaseExcerpt ->
    agent >> " set a " >> link viewAssetSegment (HTML, volumeId <$> notificationVolume, Id $ SlotId (fromMaybe noId notificationContainerId) segment, fromMaybe noId notificationAssetId) [] "highlight"
    >> " in " >> volume >> " to " >> H.text (releaseTitle notificationRelease msg) >> "."
  NoticeExcerptVolume ->
    agent >> " created a " >> link viewAssetSegment (HTML, volumeId <$> notificationVolume, Id $ SlotId (fromMaybe noId notificationContainerId) segment, fromMaybe noId notificationAssetId) [] "highlight"
    >> " in " >> volume >> "."
  NoticeSharedVolume ->
    agent >> " shared " >> volume >> "."
  where
  target = partyRow (accountParty notificationTarget)
  person p = on (/=) partyId p target ?> htmlPartyViewLink p ([] :: Query)
  agent = fromMaybe "You" $ person notificationAgent
  partyp = any (on (/=) partyId notificationAgent) notificationParty ?$> person =<< notificationParty
  party = maybe "you" (fromMaybe "themselves") partyp
  partys = maybe "your" (maybe "their own" (>> "'s")) partyp
  partyq = ("party", maybe "" (BSC.pack . show . partyId) notificationParty)
  link u a q h = H.a H.! actionLink u a (map (second Just) q :: Query) $ h
  partyEdit = partyEditLink link target
  granted = maybe "revoked" (const "granted") notificationPermission
  volume = maybe "<VOLUME>" (\v -> htmlVolumeViewLink v ([] :: Query)) notificationVolume
  volumeEdit = link viewVolumeEdit (maybe noId volumeId notificationVolume)
  perm = fromMaybe PermissionNONE notificationPermission
  segment = fromMaybe fullSegment notificationSegment
  noId :: Num (IdType a) => Id a
  noId = (Id $ -1)
