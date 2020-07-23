// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package gpp.sso.model

import cats.ApplicativeError
import cats.implicits._
import java.security.AccessControlException
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax._
import io.circe.generic.semiauto._
import io.circe.Decoder
import io.circe.DecodingFailure

/** A user has [at least] an identity and a role. */
sealed trait User extends Product with Serializable {

  def id:   User.Id
  def role: Role

  /**
   * A name to display in interfaces. This is never empty, and respects users' formatting preferences
   * as given in their ORCID record.
   */
  def displayName: String

  /** Verity that this user has access greater than or equal to `access`. */
  final def verifyAccess[F[_]](access: Access)(
    implicit ev: ApplicativeError[F, Throwable]
  ): F[Unit] =
    ev.raiseError(new AccessControlException(s"$displayName (User ${id.value}, $role) does not have required access $access."))
      .whenA(role.access < access)

}

object User {


  trait Id {
    def value: Int
    final override def toString = value.toString
  }

  implicit val EncoderUser: Encoder[User] =
    Encoder.instance {
      case GuestUser(id) =>
        Json.obj(
          "type" -> "guest".asJson,
          "id"   -> id.asJson
        )
      case ServiceUser(id, name) =>
        Json.obj(
          "type" -> "service".asJson,
          "id"   -> id.asJson,
          "name" -> name.asJson,
        )
      case StandardUser(id, _, _, _) => // TODO
        Json.obj(
          "type" -> "standard".asJson,
          "id"   -> id.asJson,
        )

    }

  implicit val DecodeUser: Decoder[User] = hc =>
    hc.downField("type").as[String].flatMap {

      case "guest" =>
        for {
          id <- hc.downField("id").as[GuestUser.Id]
        } yield GuestUser(id)

      // TODO: others

      case tag  =>
        Left(DecodingFailure(s"Unknown user type: $tag", Nil))

    }

}


/**
 * Guest users have the lowest access and no identifying information.
 */
final case class GuestUser(id: GuestUser.Id) extends User {
  val role = GuestRole
  val displayName = "Guest User"
}
object GuestUser {
  final case class Id(value: Int) extends User.Id
  object Id {
    implicit val encoder: Encoder[Id] = deriveEncoder
    implicit val decoder: Decoder[Id] = deriveDecoder
  }
}


/**
 * Service users have the highest access and represent services themselves.
 */
final case class ServiceUser(id: ServiceUser.Id, name: String) extends User {
  val role = ServiceRole(name)
  val displayName = s"Service User ($name)"
}
object ServiceUser {
  final case class Id(value: Int) extends User.Id
  object Id {
    implicit val encoder: Encoder[Id] = deriveEncoder
    implicit val decoder: Decoder[Id] = deriveDecoder
  }
}


/**
 * Standard users are authenticated and have a profile, as well as a set of other roles they can
 * assume.
 */
final case class StandardUser(
  id:         StandardUser.Id,
  role:       StandardRole,
  otherRoles: List[StandardRole],
  profile:    OrcidProfile
) extends User {
  val displayName = profile.displayName
}
object StandardUser {
  final case class Id(value: Int) extends User.Id
  object Id {
    implicit val encoder: Encoder[Id] = deriveEncoder
    implicit val decoder: Decoder[Id] = deriveDecoder
  }
}
