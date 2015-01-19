{-# LANGUAGE OverloadedStrings #-}
module Databrary.Identity
  ( module Databrary.Types.Identity
  , getIdentity
  , identityParty
  ) where

import Databrary.Model.Party
import Databrary.Model.Authorize
import Databrary.Model.Token
import Databrary.Action.App
import Databrary.Web.Cookie
import Databrary.Types.Identity

nobodyIdentity :: Identity
nobodyIdentity = Identity
  { identityAuthorization = nobodyAuthorization
  , identitySuperuser = False
  }

makeIdentity :: SessionToken -> Identity
makeIdentity tok = Identity
  { identityAuthorization = sessionAuthorization tok
  , identitySuperuser = False
  }

getIdentity :: AppM r Identity
getIdentity = do
  c <- getSignedCookie "session"
  s <- maybe (return Nothing) lookupSession c
  return $ maybe nobodyIdentity makeIdentity s

identityParty :: Identity -> Party
identityParty = authorizeChild . identityAuthorization