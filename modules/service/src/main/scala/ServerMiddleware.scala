// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.sso.service

import natchez.Trace
import cats._
import org.http4s.HttpRoutes
import cats.effect._
import lucuma.sso.service.config.Environment
import lucuma.sso.service.config.Environment._
import org.http4s.server.middleware.ErrorAction
import org.typelevel.log4cats.Logger
import org.http4s.server.middleware.CORS
import lucuma.sso.service.config.Config
import natchez.http4s.NatchezMiddleware
import scala.concurrent.duration._

/** A module of all the middlewares we apply to the server routes. */
object ServerMiddleware {

  type Middleware[F[_]] = Endo[HttpRoutes[F]]

  /** A middleware that adds distributed tracing. */
  def natchez[F[_]: MonadCancel[*[_], Throwable]: Trace]: Middleware[F] =
    NatchezMiddleware.server[F]

  /** A middleware that logs request and response. Headers are redacted in staging/production. */
  def logging[F[_]: Async](
    env:          Environment,
  ): Middleware[F] =
    org.http4s.server.middleware.Logger.httpRoutes[F](
      logHeaders        = true,
      logBody           = false,
      redactHeadersWhen = { _ =>
        env match {
          case Local                => false
          case Review | Staging | Production => false // TODO: Headers.SensitiveHeaders.contains(h)
        }
      }
    )

  /** A middleware that reports errors during requets processing. */
  def errorReporting[F[_]: MonadError[*[_], Throwable]: Logger]: Middleware[F] = routes =>
    ErrorAction.httpRoutes.log(
      httpRoutes              = routes,
      messageFailureLogAction = Logger[F].error(_)(_),
      serviceErrorLogAction   = Logger[F].error(_)(_)
    )

  /** A middleware that adds CORS headers. The origin must match the cookie domain. */
  def cors[F[_]: Monad](domain: String): Middleware[F] =
    CORS.policy
      .withAllowCredentials(true)
      .withAllowOriginHost(_.host.value.endsWith(domain))
      .withMaxAge(1.day)
      .apply

  /** A middleware that composes all the others defined in this module. */
  def apply[F[_]: Async: Trace: Logger](
    config: Config,
  ): Middleware[F] =
    List[Middleware[F]](
      cors(config.cookieDomain),
      logging(config.environment),
      natchez,
      errorReporting,
    ).reduce(_ andThen _) // N.B. the monoid for Endo uses `compose`

}