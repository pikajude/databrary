package models

import play.api.Play.current
import play.api.db.slick
import             slick.DB
import             slick.Config.driver.simple._
import java.sql.Timestamp

object SitePermission extends DBEnum("site_permission") {
  val NONE, BROWSE, FULL, GRANT = Value
}

object UserPermission extends DBEnum("user_permission") {
  val NONE, VIEW, EDIT, ADMIN = Value
}

case class Trust(child : Int, parent : Int, var access : SitePermission.Value, var delegate : UserPermission.Value, var authorized : Option[Timestamp], var expires : Option[Timestamp]) extends TableRow {
  def commit = DB.withSession { implicit session =>
    Trust.byKey(child, parent).map(_.mutable) update (access, delegate, authorized, expires)
  }
  def add = DB.withSession { implicit session =>
    Trust.* insert this
  }
  def remove = DB.withSession { implicit session =>
    Trust.delete(child, parent)
  }

  lazy val childEntity : Entity = Entity.get(child)
  lazy val parentEntity : Entity = Entity.get(parent)
}

object Trust extends Table[Trust]("trust") {
  def child = column[Int]("child")
  def parent = column[Int]("parent")
  def access = column[SitePermission.Value]("access")
  def delegate = column[UserPermission.Value]("delegate")
  def authorized = column[Option[Timestamp]]("authorized")
  def expires = column[Option[Timestamp]]("expires")

  def * = child ~ parent ~ access ~ delegate ~ authorized ~ expires <> (Trust.apply _, Trust.unapply _)
  def mutable = access ~ delegate ~ authorized ~ expires

  def key = primaryKey("trust_pkey", (child, parent))
  def childEntity = foreignKey("trust_child_fkey", child, Entity)(_.id)
  def parentEntity = foreignKey("trust_parent_fkey", parent, Entity)(_.id)

  def byKey(c : Int, p : Int) = Query(this).where(t => t.child === c && t.parent === p)
  def byChild(c : Int) = Query(this).where(_.child === c).sortBy(_.authorized.nullsLast)
  def byParent(p : Int) = Query(this).where(_.parent === p).sortBy(_.authorized.nullsLast)
  def get(c : Int, p : Int) : Option[Trust] = DB.withSession { implicit session =>
    byKey(c, p).firstOption
  }
  def getParents(c : Int) : List[Trust] = DB.withSession { implicit session =>
    byChild(c).list
  }
  def getChildren(p : Int) : List[Trust] = DB.withSession { implicit session =>
    byParent(p).list
  }
  def delete(c : Int, p : Int) = DB.withSession { implicit session =>
    byKey(c, p).delete
  }

  val _access_check = SimpleFunction.unary[Int, Option[SitePermission.Value]]("trust_access_check")
  def access_check(c : Int) : SitePermission.Value = DB.withSession { implicit session =>
    Query(_access_check(c)).first.getOrElse(SitePermission.NONE)
  }

  val _delegate_check = SimpleFunction.binary[Int, Int, Option[UserPermission.Value]]("trust_delegate_check")
  def delegate_check(c : Int, p : Int) : UserPermission.Value = DB.withSession { implicit session =>
    Query(_delegate_check(c, p)).first.getOrElse(UserPermission.NONE)
  }
}
