package webapp1.backend.service

import webapp1.backend.config.AppConfig
import webapp1.backend.db.UserRepository
import webapp1.backend.security.PasswordHasher
import zio.*

import java.util.concurrent.TimeUnit

/** Auto-provisions one admin account outside production so there's always at least
  * one administrator available (summary.md). Runs once at startup; no-op if an
  * admin already exists or `APP_ENV=production`.
  */
object AdminSeeder {
  def seedIfNeeded: RIO[AppConfig & UserRepository & PasswordHasher, Unit] = {
    for {
      config <- ZIO.service[AppConfig]
      _ <- ZIO.unless(config.isProduction) {
             for {
               userRepo <- ZIO.service[UserRepository]
               hasher   <- ZIO.service[PasswordHasher]
               exists   <- userRepo.existsAdmin
               _ <- ZIO.unless(exists) {
                      for {
                        hash <- hasher.hash(config.bootstrapAdmin.password)
                        now  <- Clock.currentTime(TimeUnit.MILLISECONDS)
                        _ <- userRepo.insert(
                               config.bootstrapAdmin.email,
                               Some(hash),
                               isAdmin = true,
                               googleSubject = None,
                               theme = "light",
                               createdAt = now,
                             )
                        _ <- ZIO.logInfo(s"Bootstrap admin account created: ${config.bootstrapAdmin.email}")
                      } yield ()
                    }
             } yield ()
           }
    } yield ()
  }
}
