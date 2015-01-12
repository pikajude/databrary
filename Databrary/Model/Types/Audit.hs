{-# LANGUAGE MultiParamTypeClasses, FlexibleInstances, DataKinds, TemplateHaskell, StandaloneDeriving #-}
module Databrary.Model.Types.Audit
  ( AuditAction(..)
  ) where

import Data.Char (toUpper)
import Database.PostgreSQL.Typed.Enum (makePGEnum)

import Databrary.Snaplet.PG (useTPG)

useTPG

makePGEnum "audit_action" "AuditAction" (\(h:r) -> "AuditAction" ++ toUpper h:r)

deriving instance Show AuditAction