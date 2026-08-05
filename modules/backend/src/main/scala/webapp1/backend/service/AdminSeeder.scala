package webapp1.backend.service

import webapp1.backend.config.AppConfig
import webapp1.backend.db.UserRepository
import webapp1.backend.security.PasswordHasher
import zio.*

import java.util.concurrent.TimeUnit

/** Auto-provisions one admin account outside production so there's always at least one administrator available
  * (summary.md). Runs once at startup; no-op if an admin already exists or `APP_ENV=production`.
  */
object AdminSeeder {
  def seedIfNeeded: RIO[AppConfig & UserRepository & PasswordHasher, Unit] = {
    for {
      config <- ZIO.service[AppConfig]
      _ <-
        ZIO.unless(config.isProduction) {
          for {
            userRepo <- ZIO.service[UserRepository]
            hasher <- ZIO.service[PasswordHasher]
            exists <- userRepo.existsAdmin
            _ <-
              ZIO.unless(exists) {
                // Same normalization AuthService.login applies before looking an account up —
                // without it a BOOTSTRAP_ADMIN_EMAIL containing capitals seeds an account that
                // can never be logged into.
                val email = config.bootstrapAdmin.email.trim.toLowerCase
                for {
                  hash <- hasher.hash(config.bootstrapAdmin.password)
                  now <- Clock.currentTime(TimeUnit.MILLISECONDS)
                  // Verified on creation: nobody is going to read a link sent to a placeholder
                  // address, and a bootstrap admin that cannot sign in defeats its own purpose.
                  _ <- userRepo.insert(
                    email,
                    Some(hash),
                    isAdmin = true,
                    theme = "light",
                    createdAt = now,
                    emailVerifiedAt = Some(now),
                  )
                  _ <- ZIO.logInfo(s"Bootstrap admin account created: $email")
                } yield ()
              }
          } yield ()
        }
    } yield ()
  }
}
