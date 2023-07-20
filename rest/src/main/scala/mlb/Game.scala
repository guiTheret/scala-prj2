package mlb

import zio.json._
import zio.jdbc._

import java.time.LocalDate

object HomeTeams {

  opaque type HomeTeam = String

  object HomeTeam {

    def apply(value: String): HomeTeam = value

    def unapply(homeTeam: HomeTeam): String = homeTeam
  }

  given CanEqual[HomeTeam, HomeTeam] = CanEqual.derived
  implicit val homeTeamEncoder: JsonEncoder[HomeTeam] = JsonEncoder.string
  implicit val homeTeamDecoder: JsonDecoder[HomeTeam] = JsonDecoder.string
}

object HomeScores {

  opaque type HomeScore = Int

  object HomeScore {

    def apply(value : Int): HomeScore = value

    def unapply(homeScore : HomeScore): Int = homeScore
  }

  given CanEqual[HomeScore,HomeScore] = CanEqual.derived
  implicit val homeTeamEncoder: JsonEncoder[HomeScore] = JsonEncoder.int
  implicit val homeTeamDecoder: JsonDecoder[HomeScore] = JsonDecoder.int
}

object AwayScores {

  opaque type AwayScore = Int

  object AwayScore {

    def apply(value : Int): AwayScore = value

    def unapply(homeScore : AwayScore): Int = homeScore
  }

  given CanEqual[AwayScore,AwayScore] = CanEqual.derived
  implicit val homeTeamEncoder: JsonEncoder[AwayScore] = JsonEncoder.int
  implicit val homeTeamDecoder: JsonDecoder[AwayScore] = JsonDecoder.int
}

object AwayTeams {

  opaque type AwayTeam = String

  object AwayTeam {

    def apply(value: String): AwayTeam = value

    def unapply(awayTeam: AwayTeam): String = awayTeam
  }

  given CanEqual[AwayTeam, AwayTeam] = CanEqual.derived
  implicit val awayTeamEncoder: JsonEncoder[AwayTeam] = JsonEncoder.string
  implicit val awayTeamDecoder: JsonDecoder[AwayTeam] = JsonDecoder.string
}

object GameDates {

  opaque type GameDate = LocalDate

  object GameDate {

    def apply(value: LocalDate): GameDate = value

    def unapply(gameDate: GameDate): LocalDate = gameDate
  }

  given CanEqual[GameDate, GameDate] = CanEqual.derived
  implicit val gameDateEncoder: JsonEncoder[GameDate] = JsonEncoder.localDate
  implicit val gameDateDecoder: JsonDecoder[GameDate] = JsonDecoder.localDate
}

object SeasonYears {

  opaque type SeasonYear <: Int = Int

  object SeasonYear {

    def apply(year: Int): SeasonYear = year

    def safe(value: Int): Option[SeasonYear] =
      Option.when(value >= 1876 && value <= LocalDate.now.getYear)(value)

    def unapply(seasonYear: SeasonYear): Int = seasonYear
  }

  given CanEqual[SeasonYear, SeasonYear] = CanEqual.derived
  implicit val seasonYearEncoder: JsonEncoder[SeasonYear] = JsonEncoder.int
  implicit val seasonYearDecoder: JsonDecoder[SeasonYear] = JsonDecoder.int
}


object EloRating {
  case class Elo(elo_pre: Double, elo_prob: Double, elo_post: Double)

  def apply(elo_pre: Double, elo_prob: Double, elo_post: Double): Elo =
    Elo(elo_pre, elo_prob, elo_post)

  def safe(elo_pre: Double, elo_prob: Double, elo_post: Double): Option[Elo] =
    if (elo_prob >= 0.0 && elo_prob <= 1.0) {
      Some(Elo(elo_pre, elo_prob, elo_post))
    } else {
      None
    }

   def unapply(elo: Elo): Option[(Double, Double, Double)] = Some((elo.elo_pre, elo.elo_prob, elo.elo_post))


  implicit val eloEncoder: JsonEncoder[Elo] = DeriveJsonEncoder.gen[Elo]
  implicit val eloDecoder: JsonDecoder[Elo] = DeriveJsonDecoder.gen[Elo]
}

import GameDates.*
import SeasonYears.*
import HomeTeams.*
import AwayTeams.*
import EloRating.*
import HomeScores.HomeScore
import AwayScores.AwayScore
final case class Game(
    date: GameDate,
    season: SeasonYear,
    homeTeam: HomeTeam,
    awayTeam: AwayTeam,
    home_score : HomeScore,
    away_score : AwayScore,
    elo_home : Elo,
    elo_away: Elo
)

object Game {
  given CanEqual[Game, Game] = CanEqual.derived
  implicit val gameEncoder: JsonEncoder[Game] = DeriveJsonEncoder.gen[Game]
  implicit val gameDecoder: JsonDecoder[Game] = DeriveJsonDecoder.gen[Game]

  // Updated unapply method to include elo_home and elo_away fields
  def unapply(game: Game): (GameDate, SeasonYear, HomeTeam, AwayTeam, HomeScore, AwayScore, EloRating.Elo, EloRating.Elo) =
    (game.date, game.season,  game.homeTeam, game.awayTeam, game.home_score, game.away_score, game.elo_home, game.elo_away)

  // a custom decoder from a tuple
  type Row = (String, Int, String, String, Int, Int, Double, Double, Double, Double, Double, Double)

  extension (g: Game)
    def toRow: Row =
      val (d, y,  h, a, hs, as, elo_home, elo_away) = Game.unapply(g)
      (
        GameDate.unapply(d).toString,
        SeasonYear.unapply(y),
        HomeTeam.unapply(h),
        AwayTeam.unapply(a),
        HomeScore.unapply(hs),
        AwayScore.unapply(as),
        elo_home.elo_pre,
        elo_home.elo_prob,
        elo_home.elo_post,
        elo_away.elo_pre,
        elo_away.elo_prob,
        elo_away.elo_post
      )

  // Updated jdbcDecoder to decode elo_home and elo_away fields
  implicit val jdbcDecoder: JdbcDecoder[Game] = JdbcDecoder[Row]().map[Game] { t =>
    val (date, season, home, away,home_score, away_score, elo_home_pre, elo_home_prob, elo_home_post, elo_away_pre, elo_away_prob, elo_away_post) = t
    Game(
      GameDate(LocalDate.parse(date)),
      SeasonYear(season),
      HomeTeam(home),
      AwayTeam(away),
      HomeScore(home_score),
      AwayScore(away_score),
      EloRating.Elo(elo_home_pre, elo_home_prob, elo_home_post),
      EloRating.Elo(elo_away_pre, elo_away_prob, elo_away_post)
    )
  }
}

val games: List[Game] = List(
  Game(
    GameDate(LocalDate.parse("2021-10-03")),
    SeasonYear(2023),
    HomeTeam("ATL"),
    AwayTeam("NYM"), 
    HomeScore(24),
    AwayScore(14),
    EloRating.Elo(2000.0, 0.7, 2100.0),
    EloRating.Elo(2000.0, 0.7, 2100.0)
  )
)