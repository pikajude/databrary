package site

import play.api.mvc._
import macros._
import dbrary._
import models._
import scala._

object Site {
  type DB = com.github.mauricio.async.db.Connection
  private def getDBPool(implicit app : play.api.Application) : DB =
    app.plugin[PostgresAsyncPlugin].fold(throw new Exception("PostgresAsyncPlugin not registered"))(_.pool)
  lazy val dbPool : DB = getDBPool(play.api.Play.current)
}

trait SiteAccess extends models.Access {
  def target = Party.Root
}

/** Basic information about each request.  Primarily implemented by [[controllers.SiteRequest]]. */
trait Site extends SiteAccess {
  /** [[models.Party]] of the logged-in user, possibly [[models.Party.Nobody]]. */
  def identity : Party
  /** Some(identity) only if actual logged-in user. */
  def user : Option[models.Account] = identity.account
  /** Level of site access [[models.Permission]] current user has.
    * VIEW for anonymous, DOWNLOAD for affiliate, CONTRIBUTE for authorized, ADMIN for admins.
    */
  val access : Permission.Value
  val superuser : Boolean
  /** IP of the client's host. */
  def clientIP : dbrary.Inet
}

trait AnonSite extends Site {
  final def identity = models.Party.Nobody
  final val superuser = false
  final val access = models.Permission.NONE
  final val directAccess = models.Permission.NONE
}

trait AuthSite extends Site {
  val token : SessionToken
  val account : Account = token.account
  final def identity = account.party
  override def user = Some(account)
  final val access = token.access
  final val directAccess = token.directAccess
}

trait PerSite {
  implicit def site : Site
}

/** An object with a corresponding page on the site. */
trait SitePage {
  /** The title of the object/page in the hierarchy, which may only make sense within [[pageParent]]. */
  def pageName : String
  /** Optional override of pageName for breadcrumbs and other abbreviated locations */
  def pageCrumbName : Option[String] = None
  /** The object "above" this one (in terms of breadcrumbs and nesting). */
  def pageParent : Option[SitePage]
  /** The URL of the page, usually determined by [[controllers.routes]]. */
  def pageURL : play.api.mvc.Call
}

trait SiteObject extends SitePage with HasPermission {
  def json : JsonValue
}
