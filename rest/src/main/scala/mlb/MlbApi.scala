package mlb

import zio._
import zio.jdbc._
import zio.http._
import com.github.tototoshi.csv._
import java.io.File
import zio.stream.ZStream
import GameDates.*
import SeasonYears.*
import HomeTeams.*
import AwayTeams.*
import HomeScores.*
import AwayScores.*


import java.sql.Date
import java.time.LocalDate

object MlbApi extends ZIOAppDefault {

  import DataService._
  import ApiService._

  val static: App[Any] = Http.collect[Request] {
    case Method.GET -> Root / "text" => Response.text("Hello MLB Fans!")
    case Method.GET -> Root / "json" => Response.json("""{"greetings": "Hello MLB Fans!"}""")
  }.withDefaultErrorResponse

  val endpoints: App[ZConnectionPool] = Http.collectZIO[Request] {
    /*
    case Method.GET -> Root / "init" =>
        for {
          _ <- initializeDatabase
          res: Response = Response.text("Database initialized")
        } yield res
    */
    case Method.GET -> Root / "game" / "latest" / homeTeam / awayTeam =>
      for {
        game: Option[Game] <- latest(HomeTeam(homeTeam), AwayTeam(awayTeam))
        res: Response = latestGameResponse(game)
      } yield res
    case Method.GET -> Root / "game" / "predict" / homeTeam / awayTeam =>
      // FIXME : implement correct logic and response
      ZIO.succeed(Response.text(s"$homeTeam vs $awayTeam win probability: 0.0"))
    case Method.GET -> Root / "games" / "count" =>
      for {
        count: Option[Int] <- count
        res: Response = countResponse(count)
      } yield res
     case Method.GET -> Root / "games" / "history" / homeTeam =>
      for {
        games: List[Game] <- history(HomeTeam(homeTeam))
        res: Response = historyResponse(games)
      } yield res
    case _ =>
      ZIO.succeed(Response.text("Not Found").withStatus(Status.NotFound))
  }.withDefaultErrorResponse

  val appLogic: ZIO[ZConnectionPool & Server, Throwable, Unit] = for {
    _ <- for {
      conn <- create
      source <- ZIO.succeed(
        CSVReader
          .open(new File("mlb_elo.csv"))
      )
      stream <- ZStream
        .fromIterator[Seq[String]](source.iterator)
        .filter(row => row.nonEmpty && row(0) != "date")
        .map[Game](row =>
          Game(
            GameDate(LocalDate.parse(row(0))),
            SeasonYear(row(1).toInt),
            HomeTeam(row(4)),
            AwayTeam(row(5)),
            HomeScore(row(24).toIntOption.getOrElse(-1)),
            AwayScore(row(25).toIntOption.getOrElse(-1)),
            EloRating(row(6).toDoubleOption.getOrElse(-1), row(8).toDoubleOption.getOrElse(-1), row(10).toDoubleOption.getOrElse(-1)),
            EloRating(row(7).toDoubleOption.getOrElse(-1), row(9).toDoubleOption.getOrElse(-1), row(11).toDoubleOption.getOrElse(-1))
          )
        )
        .grouped(1000)
        // Insert 1000 rows at a time to the database
        .foreach(chunk => insertRows(chunk.toList))
      _ <- ZIO.succeed(source.close())
      // print the number of rows inserted
      _ <- count.flatMap(c => ZIO.succeed(println(s"Inserted $c rows")))
      res <- ZIO.succeed(conn)
    } yield res
    _ <- Server.serve[ZConnectionPool](static ++ endpoints)
  } yield ()

   override def run: ZIO[Any, Throwable, Unit] =
    appLogic
      .provide(
        createZIOPoolConfig >>> connectionPool,
        Server.defaultWithPort(8080)
      )
}

object ApiService {
  import zio.json.EncoderOps

  def countResponse(count: Option[Int]): Response = {
    count match
      case Some(c) => Response.text(s"$c game(s) in historical data").withStatus(Status.Ok)
      case None => Response.text("No game in historical data").withStatus(Status.NotFound)
  }

  def latestGameResponse(game: Option[Game]): Response = {
    println(game)
    game match
      case Some(g) => Response.json(g.toJson).withStatus(Status.Ok)
      case None => Response.text("No game found in historical data").withStatus(Status.NotFound)
  }

  def historyResponse(games: List[Game]): Response = {
     games match {
    case Nil =>
      Response.text("No games found in historical data").withStatus(Status.NotFound)
    case _ =>
      Response.json(games.toJson).withStatus(Status.Ok)
  }
}
}

object DataService {

  val createZIOPoolConfig: ULayer[ZConnectionPoolConfig] =
    ZLayer.succeed(ZConnectionPoolConfig.default)

  val properties: Map[String, String] = Map(
    "user" -> "postgres",
    "password" -> "postgres"
  )

  val connectionPool: ZLayer[ZConnectionPoolConfig, Throwable, ZConnectionPool] =
    ZConnectionPool.h2mem(
      database = "mlb",
      props = properties
    )

  val create: ZIO[ZConnectionPool, Throwable, Unit] = transaction {
    execute(
      sql"DROP TABLE IF EXISTS games"
    ) *> execute(
      sql"CREATE TABLE IF NOT EXISTS games(date DATE NOT NULL, season_year INT NOT NULL, playoff_round INT, home_team VARCHAR(3), away_team VARCHAR(3), home_score INT, away_score INT, elo1_pre DOUBLE PRECISION NOT NULL, elo1_prob DOUBLE PRECISION NOT NULL, elo1_post DOUBLE PRECISION NOT NULL, elo2_pre DOUBLE PRECISION NOT NULL, elo2_prob DOUBLE PRECISION NOT NULL, elo2_post DOUBLE PRECISION NOT NULL)"
    )
  }
  // Should be implemented to replace the `val insertRows` example above. Replace `Any` by the proper case class.
  def insertRows(
      games: List[Game]
  ): ZIO[ZConnectionPool, Throwable, UpdateResult] = {
    val rows: List[Game.Row] = games.map(_.toRow)
    transaction {
      insert(
        sql"INSERT INTO games(date, season_year, home_team, away_team, home_score, away_score, elo1_pre,elo2_pre,elo_prob1, elo_prob2,elo1_post ,elo2_post)"
          .values[Game.Row](rows)
      )
    }
  }

  val count: ZIO[ZConnectionPool, Throwable, Option[Int]] = transaction {
    selectOne(
      sql"SELECT COUNT(*) FROM games".as[Int]
    )
  }

  def latest(homeTeam: HomeTeam, awayTeam: AwayTeam): ZIO[ZConnectionPool, Throwable, Option[Game]] = {
    transaction {
      selectOne(
        sql"SELECT * FROM games WHERE home_team = ${HomeTeam.unapply(homeTeam)} AND away_team = ${AwayTeam.unapply(awayTeam)} ORDER BY date DESC LIMIT 1".as[Game]
      )
    }
  }

  def history(homeTeam: HomeTeam): ZIO[ZConnectionPool, Throwable, List[Game]] = transaction {
    selectAll(
      sql"""
        SELECT home_team, away_team, home_score, away_score, date
        FROM games
        WHERE home_team = ${HomeTeam.unapply(homeTeam)} OR away_team = ${HomeTeam.unapply(homeTeam)}
        ORDER BY date DESC
      """.as[Game]
    ).map(_.toList)
  }
}
