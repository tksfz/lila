package lila
package tournament

import org.joda.time.DateTime
import org.scala_tools.time.Imports._
import scalaz.effects._
import scalaz.{ NonEmptyList, Success, Failure }
import play.api.libs.json._

import game.DbGame
import user.User

final class TournamentApi(
    repo: TournamentRepo,
    joiner: GameJoiner,
    socket: Socket,
    siteSocket: site.Socket,
    lobbyNotify: String ⇒ IO[Unit],
    roundMeddler: round.Meddler,
    incToints: String ⇒ Int ⇒ IO[Unit]) {

  def makePairings(tour: Started, pairings: NonEmptyList[Pairing]): IO[Unit] =
    (tour addPairings pairings) |> { tour2 ⇒
      for {
        _ ← repo saveIO tour2
        games ← (pairings map joiner(tour)).sequence
        _ ← (games map socket.notifyPairing).sequence
      } yield ()
    }

  def createTournament(setup: TournamentSetup, me: User): IO[Created] = for {
    withdrawIds ← repo withdraw me.id
    created = Tournament(
      createdBy = me,
      clock = TournamentClock(setup.clockTime * 60, setup.clockIncrement),
      minutes = setup.minutes,
      minPlayers = setup.minPlayers)
    _ ← repo saveIO created
    _ ← (withdrawIds map socket.reload).sequence
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield created

  def start(created: Created): IO[Unit] = (for {
    _ ← repo saveIO created.start
    _ ← socket reload created.id
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()) doIf created.readyToStart

  def wipeEmpty(created: Created): IO[Unit] = (for {
    _ ← repo removeIO created
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()) doIf created.isEmpty

  def finish(started: Started): IO[Tournament] = started.readyToFinish.fold({
    val pairingsToAbort = started.playingPairings
    val finished = started.finish
    for {
      _ ← repo saveIO finished
      _ ← socket reloadPage finished.id
      _ ← reloadSiteSocket
      _ ← (pairingsToAbort map (_.gameId) map roundMeddler.forceAbort).sequence
      _ ← finished.players.filter(_.score > 0).map(p ⇒ incToints(p.id)(p.score)).sequence
    } yield finished
  }, io(started))

  def join(tour: Created, me: User): Valid[IO[Unit]] = for {
    tour2 ← tour join me
  } yield for {
    withdrawIds ← repo withdraw me.id
    _ ← repo saveIO tour2
    _ ← socket.notifyJoining(tour.id, me.id)
    _ ← ((tour.id :: withdrawIds) map socket.reload).sequence
    _ ← reloadSiteSocket
    _ ← lobbyReload
  } yield ()

  def withdraw(tour: Tournament, userId: String): IO[Tournament] = tour match {
    case created: Created ⇒ (created withdraw userId).fold(
      err ⇒ putStrLn(err.shows) inject tour,
      tour2 ⇒ for {
        _ ← repo saveIO tour2
        _ ← socket reload tour2.id
        _ ← reloadSiteSocket
        _ ← lobbyReload
      } yield tour2
    )
    case started: Started ⇒ (started withdraw userId).fold(
      err ⇒ putStrLn(err.shows) inject tour,
      tour2 ⇒ for {
        _ ← repo saveIO tour2
        _ ← (tour2 userCurrentPov userId).fold(roundMeddler.resign, io())
        _ ← socket reload tour2.id
        _ ← reloadSiteSocket
      } yield tour2
    )
    case finished: Finished ⇒ putStrLn("Cannot withdraw from finished tournament " + finished.id) inject tour
  }

  def finishGame(game: DbGame): IO[Option[Tournament]] = for {
    tourOption ← game.tournamentId.fold(repo.startedById, io(None))
    result ← tourOption.filter(_ ⇒ game.finished).fold(
      tour ⇒ {
        val tour2 = tour.updatePairing(game.id, _.finish(game.status, game.winnerUserId))
        repo saveIO tour2 inject tour2.some
      },
      io(none)
    )
  } yield result

  private def userIdWhoLostOnTimeWithoutMoving(game: DbGame): Option[String] =
    game.playerWhoDidNotMove
      .flatMap(_.userId)
      .filter(_ ⇒ List(chess.Status.Timeout, chess.Status.Outoftime) contains game.status)

  private def lobbyReload = for {
    tours ← repo.created
    _ ← lobbyNotify(views.html.tournament.createdTable(tours).toString)
  } yield ()

  private val reloadMessage = JsObject(Seq("t" -> JsString("reload"), "d" -> JsNull))
  private def sendToSiteSocket(message: JsObject) = io {
    siteSocket.sendToFlag("tournament", message)
  }
  private val reloadSiteSocket = sendToSiteSocket(reloadMessage)
}
