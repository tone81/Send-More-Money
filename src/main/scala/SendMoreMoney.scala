package net.nebupookins.akka.sendmoremoney
 
import akka.actor._
import akka.routing.RoundRobinRouter
import akka.util.Duration
import akka.util.duration._

import com.typesafe.config._

import java.io.File

import scala.collection._
import scala.io.Source

object SendMoreMoney {
  /**
   * 
   */
  class ResultPrinter(appConf: Config) extends Actor with ActorLogging {
    val numSolutions: Int = appConf.getInt("max-solutions-to-find")
    def receive = {
      case PotentialMatch(word1, word2, word3, addProofs, Some(usageProof)) =>
        if (addProofs.nonEmpty) {
          val addProofsString =
            addProofs.take(numSolutions).map(proof =>
              "%d + %d = %d".format(proof.operand1, proof.operand2, proof.total)
            ).mkString(", ")
          val moreStr = if (addProofs.size > numSolutions) ", ..." else ""
          println(
            "\n\t  %10s\n\t+ %10s\n\t============\n\t  %10s\nE.g. %s\nSolutions: %s%s\n"
            .format(word1, word2, word3, usageProof, addProofsString, moreStr)
          )
          sender ! ResultPrinted
        } else {
          log.error("Should not have sent %s %s %s if there are no add proofs.".format(word1, word2, word3))
        }
        
    }
  }

  class Master(appConf: Config) extends Actor with ActorLogging {
    val numSumCheckers = appConf.getInt("number-of-sum-checkers")
    val minimumWordLength = appConf.getInt("minimum-word-length")
    val allowDuplicateOperands = appConf.getBoolean("allow-duplicate-operand")
    val allowNonUniqueSolutions = appConf.getBoolean("allow-non-unique-solutions")
    val numProcessors = Runtime.getRuntime().availableProcessors()
    if (numSumCheckers < numProcessors) {
      log.warning(
        "WARNING: You have set the number of Sum Checkers to be %d, but you seem to ".format(numSumCheckers) +
        "have %d cores available. You may achieve higher parallelism and thus ".format(numProcessors) +
        "higher performance is you increase the number of Sum Checkers in the " +
        "application.conf file."
      )
    }
    val wordFiles: List[File] = {
      import scala.collection.JavaConverters._
      val tempWordFiles = for(
        fileName: String <- appConf.getStringList("dictionary-files").asScala.toList
      ) yield {
        new File(fileName).getAbsoluteFile
      }
      val (existing, nonExisting) = tempWordFiles.partition(_.exists())
      for (file <- nonExisting) {
        log.warning("Could not find file %s. Skipping it.".format(file))
      }
      existing
    }
    val words: Set[String] = {
      log.info("Received files: %s ".format(wordFiles))
      val temp: Traversable[String] = (for (file <- wordFiles) yield {
        log.info("Processing %s...".format(file))
        val lines: Iterator[String] = Source.fromFile(file, "UTF-8").getLines
        lines
      }).flatten.filter(_.length >= minimumWordLength)
      assert(temp.nonEmpty, "temp.size == %d".format(temp.size))
      temp.map(_.toUpperCase).toSet
    }
    assert(words.nonEmpty, "words.size == %d".format(words.size))
    val sumCheckerRouter = context.actorOf(
        Props.empty.withRouter(RoundRobinRouter(routees = {
          for (_ <- 0 until numSumCheckers) yield context.actorOf(Props(new SumChecker(appConf)))
        })), name = "SumCheckerRouter")
    val freqchecker = context.actorOf(Props(new FrequencyChecker(appConf)), name = "FrequencyChecker")
    val resultPrinter = context.actorOf(Props(new ResultPrinter(appConf)), name = "ResultPrinter")

    var wordsSentToAddCheck = 0
    var wordsAddChecked = 0
    var wordsSentToUsageCheck = 0
    var wordsUsageChecked = 0
    var wordsSentToResultPrinter = 0
    var resultsPrinted = 0

    val logShutdownChecks = appConf.getBoolean("logging.master.shutdown-checks")
    def checkShutdown() {
      if (logShutdownChecks) {
        log.debug(
          "Checking shutdown. add: %d == %d? usage %d == %d? print %d == %d?".format(
            wordsSentToAddCheck, wordsAddChecked, wordsSentToUsageCheck,
            wordsUsageChecked, wordsSentToResultPrinter, resultsPrinted
          )
        )
      }
      if (
        wordsSentToAddCheck == wordsAddChecked &&
        wordsSentToUsageCheck == wordsUsageChecked &&
        wordsSentToResultPrinter == resultsPrinted
      ) {
        log.info("All work done. Shutting down.")
        context.system.shutdown()
      }
    }

    def receive = {
      case Start =>
        log.info("Master received start message. Starting. Length of words is %d.".format(words.size))
        for (
          word1 <- words;
          word2 <- words;
          word3 <- words
        ) {
          if (allowDuplicateOperands || word1 != word2) {
            sumCheckerRouter ! CheckAdds(PotentialMatch(word1, word2, word3, Set.empty, None))
            wordsSentToAddCheck += 1
          }
        }
        log.info("Master has finished sending out the wordlist.")
      case PotentialMatch(word1, word2, wordtotal, addProofs, None) =>
        if (addProofs.nonEmpty) {
          if (allowNonUniqueSolutions || addProofs.size == 1) {
            freqchecker ! PotentialMatch(word1, word2, wordtotal, addProofs, None)
            wordsSentToUsageCheck += 1
          }
        }
      case PotentialMatch(word1, word2, wordtotal, addProofs, Some(usageProof: String)) =>
        if (addProofs.nonEmpty) {
          resultPrinter ! PotentialMatch(word1, word2, wordtotal, addProofs, Some(usageProof))
          wordsSentToResultPrinter += 1
        }
      case AddChecked =>
        wordsAddChecked += 1
        checkShutdown()
      case UsageChecked =>
        wordsUsageChecked += 1
        checkShutdown()
      case ResultPrinted =>
        resultsPrinted += 1
        checkShutdown()
    }
  }

  def main(args: Array[String]) {
    val rootConf: Config = ConfigFactory.load()
    val appConf: Config = rootConf.getConfig("send-more-money")
    if (
      appConf.getString("twitter-authentication.consumer-key") == "XXX" ||
      appConf.getString("twitter-authentication.consumer-secret") == "XXX" ||
      appConf.getString("twitter-authentication.access-token") == "XXX" ||
      appConf.getString("twitter-authentication.access-token-secret") == "XXX"
    ) {
      System.err.println("You must edit the application.conf file and fill in")
      System.err.println("the values for the consumer-key, consumer-secret,")
      System.err.println("access-token and access-token-secret under the")
      System.err.println("twitter-authentication section. Instructions for how")
      System.err.println("to do this are provided in the application.conf file.")
    } else {
      val system = ActorSystem("SendMoreMoneySystem")
      val master = system.actorOf(Props(new Master(appConf)), name = "master")
      master ! Start
    }
  }

  class SumChecker(appConf: Config) extends Actor with ActorLogging {
    val numSolutions = appConf.getInt("max-solutions-to-find")
    val logDigitPermutations = appConf.getBoolean("logging.sum-checker.digit-permutations")
    val logEntry = appConf.getBoolean("logging.sum-checker.entry")
    val logRecursiveAssignmentSearch = appConf.getBoolean("logging.sum-checker.recursive-assignment-search")
    val all10Digits = (0 until 10).toSet

    def uniqueLetters(words: String*): Set[Char] = {
      words.mkString("").toSet
    }

    val requirements: List[(String, String, String) => Boolean] = List(
      //10 or fewer distinct letters
      { (operand1: String, operand2: String, total: String) =>
        uniqueLetters(operand1, operand2, total).size <= 10
      },
      //total is distinct from both operands
      { (operand1: String, operand2: String, total: String) =>
        total != operand1 && total != operand2
      },
      /*
       * The number of "digits" in the two operands is less than or equal to the
       * number of "digits" in the total, and the total is no more than 1
       * "digit" bigger than either of the operands.
       */
      { (operand1: String, operand2: String, total: String) =>
        operand1.length <= total.length &&
        operand2.length <= total.length &&
        total.length <= Math.max(operand1.length, operand2.length) + 1
      }
    )

    def wordToNumber(word: String, mapping: Map[Char, Int]): Long = {
      val numericWord: String = word.map({case char: Char =>
        ('0' + mapping(char)).toChar
      })
      numericWord.toLong
    }

    def getLegalAssignments(
      operand1: String, operand2: String, total: String,
      unassignedLetters: Set[Char], unassignedNumbers: Set[Int],
      mappingSoFar: Map[Char, Int], carry: Int
    ): Set[Map[Char, Int]] = {
      assert(carry >= 0, "Carry was %d.".format(carry))
      if (logRecursiveAssignmentSearch) {
        log.debug("Trying to find an assignment for %s + %s + %d = %s".format(operand1, operand2, carry, total))
      }
      def tryANumberFor(letter: Char): Set[Map[Char, Int]] = {
        (for (number <- unassignedNumbers) yield {
          getLegalAssignments(
            operand1, operand2, total,
            unassignedLetters - letter, unassignedNumbers - number,
            mappingSoFar + (letter -> number), carry
          )
        }).flatten
      }
      //Reject any solutions that assign 0 to the left-most character
      if (operand1.nonEmpty && mappingSoFar.get(operand1.head) == Some(0)) {
        Set.empty
      } else if (operand2.nonEmpty && mappingSoFar.get(operand2.head) == Some(0)) {
        Set.empty
      } else if (total.nonEmpty && mappingSoFar.get(total.head) == Some(0)) {
        Set.empty
      } else if (unassignedLetters.isEmpty) { //Base case: We have a full mapping; check if it's valid.
        val number1 = if (operand1.isEmpty) 0 else wordToNumber(operand1, mappingSoFar)
        val number2 = if (operand2.isEmpty) 0 else wordToNumber(operand2, mappingSoFar)
        val numberTotal = if (total.isEmpty) 0 else wordToNumber(total, mappingSoFar)
        if (number1 + number2 + carry == numberTotal) {
          if (logRecursiveAssignmentSearch) {
            log.debug("No more unassigned letters. Solution found.")
          }
          Set(mappingSoFar)
        } else {
          if (logRecursiveAssignmentSearch) {
            log.debug("No more unassigned letters. No solution found.")
          }
          Set.empty
        }
      } else if (operand1.nonEmpty && unassignedLetters.contains(operand1.last)) { //Try to assign rightmost letter in operand1
        if (logRecursiveAssignmentSearch) {
          log.debug("Attempting to assign right-most letter in %s.".format(operand1))
        }
        tryANumberFor(operand1.last)
      } else if (operand2.nonEmpty && unassignedLetters.contains(operand2.last)) { //Try to assign rightmost letter in operand2
        if (logRecursiveAssignmentSearch) {
          log.debug("Attempting to assign right-most letter in %s.".format(operand2))
        }
        tryANumberFor(operand2.last)
      } else if (unassignedLetters.contains(total.last)) { //We can determine what that letter must be
        val letter = total.last
        val operand1LastDigit = if (operand1.isEmpty) 0 else mappingSoFar(operand1.last)
        val operand2LastDigit = if (operand2.isEmpty) 0 else mappingSoFar(operand2.last)
        val sumOfLastDigits: Int = operand1LastDigit + operand2LastDigit + carry
        val lastDigitOfTotal: Int = sumOfLastDigits % 10
        val newCarry: Int = (sumOfLastDigits - lastDigitOfTotal) / 10
        val newOperand1 = if (operand1.isEmpty) "" else operand1.init
        val newOperand2 = if (operand2.isEmpty) "" else operand2.init
        assert(
          newCarry >= 0,
          "operand1LastDigit: %d operand2LastDigit: %d sumOfLastDigits: %d lastDigitOfTotal: %d newCarry: %d.".format(
            operand1LastDigit, operand2LastDigit, sumOfLastDigits, lastDigitOfTotal, newCarry
          )
        )
        if (unassignedNumbers.contains(lastDigitOfTotal)) {
          if (logRecursiveAssignmentSearch) {
            log.debug("Inferring that %s must be %d in %s.".format(letter, lastDigitOfTotal, total))
          }
          getLegalAssignments(
            newOperand1, newOperand2, total.init,
            unassignedLetters - letter, unassignedNumbers - lastDigitOfTotal,
            mappingSoFar + (letter -> lastDigitOfTotal), newCarry
          )
        } else {
          if (logRecursiveAssignmentSearch) {
            log.debug("Found contradiction: %s must be %d in %s, but that number is already used.".format(letter, lastDigitOfTotal, total))
          }
          Set.empty
        }
      } else { //We can check for a contradiction
        val letter = total.last
        val expectedNumber = mappingSoFar(letter)
        val operand1LastDigit = if (operand1.isEmpty) 0 else mappingSoFar(operand1.last)
        val operand2LastDigit = if (operand2.isEmpty) 0 else mappingSoFar(operand2.last)
        val sumOfLastDigits: Int = operand1LastDigit + operand2LastDigit + carry
        val actualNumber: Int = sumOfLastDigits % 10
        val newCarry: Int = (sumOfLastDigits - actualNumber) / 10
        assert(
          newCarry >= 0,
          "operand1LastDigit: %d operand2LastDigit: %d sumOfLastDigits: %d actualNumber: %d newCarry: %d.".format(
            operand1LastDigit, operand2LastDigit, sumOfLastDigits, actualNumber, newCarry
          )
        )
        val newOperand1 = if (operand1.isEmpty) "" else operand1.init
        val newOperand2 = if (operand2.isEmpty) "" else operand2.init
        if (expectedNumber == actualNumber) {
          if (logRecursiveAssignmentSearch) {
            log.debug("Verified that %s is %d in %s.".format(letter, expectedNumber, total))
          }
          getLegalAssignments(
            newOperand1, newOperand2, total.init,
            unassignedLetters, unassignedNumbers,
            mappingSoFar, newCarry
          )
        } else {
          if (logRecursiveAssignmentSearch) {
            log.debug("Found contradiction when %s is both %d and %d in %s.".format(letter, expectedNumber, actualNumber, total))
          }
          Set.empty
        }
      }
    }

    def receive = {
      case CheckAdds(PotentialMatch(operand1, operand2, total, _, maybeFreq)) =>
        if (logEntry) {
          log.debug("SumChecker received %s + %s = %s?".format(operand1, operand2, total))
        }
        if (requirements.forall(predicate => predicate(operand1, operand2, total))) {
          //check that some permutation of letter assignment makes a valid sum
          log.debug("Checking that %s + %s = %s".format(operand1, operand2, total))
          val letters: Set[Char] = uniqueLetters(operand1, operand2, total)
          val addProofs: Set[AddProof] =
            for (
              assignment <- getLegalAssignments(
                operand1, operand2, total,
                letters, all10Digits,
                Map.empty, 0
              )
            ) yield {
              val number1 = wordToNumber(operand1, assignment)
              val number2 = wordToNumber(operand2, assignment)
              val numberTotal = wordToNumber(total, assignment)
              AddProof(number1, number2, numberTotal)
            }
          if (addProofs.nonEmpty) {
            log.debug("Found sum solutions for %s + %s = %s".format(operand1, operand2, total))
            sender ! PotentialMatch(operand1, operand2, total, addProofs, maybeFreq)
          } else {
            log.debug("Discarded impossible sum for %s + %s = %s".format(operand1, operand2, total))
          }
        }
        sender ! AddChecked
    }
  }

  class FrequencyChecker(appConf: Config) extends Actor with ActorLogging {
    import java.net._

    import oauth.signpost._
    import oauth.signpost.basic._

    val twitterConf = appConf.getConfig("twitter-authentication")

    val consumer = new DefaultOAuthConsumer(
      twitterConf.getString("consumer-key"),
      twitterConf.getString("consumer-secret")
    )
    consumer.setTokenWithSecret(
      twitterConf.getString("access-token"),
      twitterConf.getString("access-token-secret")
    )
    val provider = new DefaultOAuthProvider(
      "https://api.twitter.com/oauth/request_token",
      "https://api.twitter.com/oauth/access_token",
      "https://api.twitter.com/oauth/authorize"
    )

    val cache = scala.collection.mutable.Map.empty[(String,String,String), Option[String]]

    def receive = {
      case PotentialMatch(word1, word2, word3, addProofs, None) =>
        val exampleUsage: Option[String] = cache.getOrElse((word1, word2, word3), {
          log.debug("FrequencyChecker cache miss. Perform Twitter API query.")
          import java.net.URLEncoder
          val encodedWord1 = URLEncoder.encode(word1, "UTF-8")
          val encodedWord2 = URLEncoder.encode(word2, "UTF-8")
          val encodedWord3 = URLEncoder.encode(word3, "UTF-8")
          val url = new URL(
            "https://api.twitter.com/1.1/search/tweets.json?q=%%22%s%%20%s%%20%s%%22".format(encodedWord1, encodedWord2, encodedWord3)
          )
          val request: HttpURLConnection = url.openConnection().asInstanceOf[HttpURLConnection]
          consumer.sign(request)
          request.connect()
          request.getResponseCode match {
            case HttpURLConnection.HTTP_OK =>
              import net.liftweb.json._
              implicit val formats = net.liftweb.json.DefaultFormats 
              val inputStream = request.getInputStream()
              val strResponse: String = Source.fromInputStream(inputStream).mkString("")
              inputStream.close()
              val jsonResponse = net.liftweb.json.parse(strResponse)
              val statuses: List[JValue] = (jsonResponse \ "statuses").asInstanceOf[JArray].arr
              val exampleUsage: Option[String] = statuses.toList.map({case status: JValue =>
                (status \ "text").extract[String]
              }).find({case tweet: String =>
                tweet.toUpperCase().contains("%s %s %s".format(word1, word2, word3))
              })
              exampleUsage
            case HttpURLConnection.HTTP_UNAUTHORIZED =>
              val errorStream = request.getErrorStream()
              val strResponse: String = Source.fromInputStream(errorStream).mkString("")
              log.error(
                "Got response code %d %s from Twitter API. Request URL was %s. Server responded %s.".format(
                  request.getResponseCode, request.getResponseMessage, request.getURL, strResponse
                )
              )
              context.system.shutdown()
              None
            case 429 => //Too Many Requests
              val minutesToWait = 15
              log.info(
                "Hit Twitter API limit while querying %s %s %s. Waiting %d minutes before querying again.".format(
                  word1, word2, word3, minutesToWait
                )
              )
              Thread.sleep(1000 * 60 * minutesToWait)
              None //TODO: I don't actually want to store this in the cache; need to think of another way to do this
          }
        })
        if (exampleUsage.isDefined) {
          log.debug("Found example usage of %s %s %s on Twitter.".format(word1, word2, word3))
          sender ! PotentialMatch(word1, word2, word3, addProofs, exampleUsage)
        } else {
          val proof = addProofs.head
          log.debug(
            "Found no usage of %s %s %s (%d + %d = %d) on Twitter.".format(
              word1, word2, word3, proof.operand1, proof.operand2, proof.total
            )
          )
        }
        sender ! UsageChecked
    }
  }

  sealed trait SMMMessage
  case object Start extends SMMMessage
  case class AddProof(operand1: Long, operand2: Long, total: Long) extends SMMMessage
  case class PotentialMatch(operand1: String, operand2: String, total: String, addProofs: Set[AddProof], usageProof: Option[String]) extends SMMMessage
  case class CheckAdds(payload: PotentialMatch) extends SMMMessage
  case object AddChecked extends SMMMessage
  case object UsageChecked extends SMMMessage
  case object ResultPrinted extends SMMMessage
}