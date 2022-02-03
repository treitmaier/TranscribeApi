package xyz.reitmaier.transcribe.plugins

import com.mysql.cj.jdbc.Driver
import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.EnumColumnAdapter
import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.sqlite.driver.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.joda.time.LocalDateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import xyz.reitmaier.transcribe.data.*
import xyz.reitmaier.transcribe.db.Task
import xyz.reitmaier.transcribe.db.TranscribeDb
import xyz.reitmaier.transcribe.db.User
import javax.sql.DataSource

fun Application.configureDB(): TranscribeDb {
  val dbConfig = environment.config.config("database")
  val host = dbConfig.property("host").getString()
  val port = dbConfig.property("port").getString()
  val database = dbConfig.property("db").getString()
  val user = dbConfig.property("user").getString()
  val passwd = dbConfig.property("password").getString()
  val maxPoolSize = dbConfig.property("maxPoolSize").getString().toInt()
  val testing = dbConfig.propertyOrNull("testing")

//  See https://github.com/AlecStrong/sql-psi/issues/153
//  Re: ?stringtype=unspecified
//  val jdbcUrl = "jdbc:postgresql://$host:$port/$database?stringtype=unspecified"
  val url = "jdbc:mysql://$host:$port/$database"

  val datasourceConfig = HikariConfig().apply {
    jdbcUrl = url
    username = user
    password = passwd
    maximumPoolSize = maxPoolSize

    // Driver needs to be explicitly set in order to produce fatjar
    // https://github.com/brettwooldridge/HikariCP/issues/540
    driverClassName = Driver::class.java.name
  }
  val dataSource : DataSource = HikariDataSource(datasourceConfig)
  val driver : SqlDriver = dataSource.asJdbcDriver()


  val db = TranscribeDb(
    driver = driver,
    userAdapter = User.Adapter(
      idAdapter = userIdAdapter,
      mobile_numberAdapter = mobileNumberAdapter,
      mobile_operatorAdapter = mobileOperatorAdapter,
      nameAdapter = nameAdapter,
      passwordAdapter = encryptedPasswordAdapter,
      created_atAdapter = timestampAdapter,
      refresh_tokenAdapter = refreshTokenAdapter,
    ),
    taskAdapter = Task.Adapter(
      idAdapter = taskIdAdapter,
      user_idAdapter = userIdAdapter,
      provenanceAdapter = EnumColumnAdapter(),
      created_atAdapter = timestampAdapter,
      updated_atAdapter = timestampAdapter

    )
  )
  driver.migrate(db)

  environment.monitor.subscribe(ApplicationStopped) { driver.close() }

  return db

}
private fun SqlDriver.migrate(database: TranscribeDb) {
  // Settings table is version 2
  TranscribeDb.Schema.migrate(this, 1, 2)
  val settings = database.settingsQueries.getSettings().executeAsOne()
  val dbVersion = settings.version
  val schemaVersion = TranscribeDb.Schema.version
  println("Current db version: $dbVersion")
  for (version in (dbVersion until schemaVersion)) {
    println("Migrating to ${version + 1}")
    TranscribeDb.Schema.migrate(this, version, version + 1)
    database.settingsQueries.setVersion(version + 1)
  }
}
private val timestampFormat: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")

private val userIdAdapter = object : ColumnAdapter<UserId, Int> {
  override fun decode(databaseValue: Int): UserId = UserId(databaseValue)
  override fun encode(value: UserId): Int = value.value
}

private val taskIdAdapter = object : ColumnAdapter<TaskId, Int> {
  override fun decode(databaseValue: Int): TaskId = TaskId(databaseValue)
  override fun encode(value: TaskId): Int = value.value
}

private val mobileNumberAdapter = object : ColumnAdapter<MobileNumber, String> {
  override fun decode(databaseValue: String) = MobileNumber(databaseValue)
  override fun encode(value: MobileNumber) = value.value
}

private val mobileOperatorAdapter = object : ColumnAdapter<MobileOperator, String> {
  override fun decode(databaseValue: String) = MobileOperator(databaseValue)
  override fun encode(value: MobileOperator) = value.value
}

private val nameAdapter = object : ColumnAdapter<Name, String> {
  override fun decode(databaseValue: String) = Name(databaseValue)
  override fun encode(value: Name) = value.value
}

private val refreshTokenAdapter = object : ColumnAdapter<RefreshToken, String> {
  override fun decode(databaseValue: String) = RefreshToken(databaseValue)
  override fun encode(value: RefreshToken) = value.value
}

private val encryptedPasswordAdapter = object : ColumnAdapter<EncryptedPassword, String> {
  override fun decode(databaseValue: String) = EncryptedPassword(databaseValue)
  override fun encode(value: EncryptedPassword) = value.value
}

val timestampAdapter = object : ColumnAdapter<LocalDateTime, String> {
  override fun decode(databaseValue: String) = LocalDateTime.parse(databaseValue, timestampFormat)
  override fun encode(value: LocalDateTime) = value.toString(timestampFormat)
}