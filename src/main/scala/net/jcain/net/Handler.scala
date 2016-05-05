package net.jcain.net

import akka.actor.{Actor, ActorLogging, ActorRef, FSM}
import akka.io.Tcp
import akka.util.ByteString

object Handler {

  // SMTP commands
  sealed trait Command
  object C_HELO { val Pattern = """(HELO|helo) +(.*)""".r }
  final case class C_HELO(host: String) extends Command
  object C_EHLO { val Pattern = """(EHLO|ehlo) +(.*)""".r }
  final case class C_EHLO(host: String) extends Command
  object C_MAIL { val Pattern = """(MAIL FROM|mail from):(.*)""".r }
  final case class C_MAIL(from: String) extends Command
  object C_RCPT { val Pattern = """(RCPT TO|rcpt to):(.*)""".r }
  final case class C_RCPT(to: String) extends Command
  case object C_DATA extends Command
  case object C_RSET extends Command
  case object C_QUIT extends Command

  def createCommand(text: String): Option[Command] = {
    text match {
      case C_HELO.Pattern(_, host) => Some(C_HELO(host))
      case C_EHLO.Pattern(_, host) => Some(C_EHLO(host))
      case C_MAIL.Pattern(_, from) => Some(C_MAIL(from))
      case C_RCPT.Pattern(_, to) => Some(C_RCPT(to))
      case "DATA" | "data" => Some(C_DATA)
      case "RSET" | "rset" => Some(C_RSET)
      case "QUIT" | "quit" => Some(C_QUIT)
      case _ => None
    }
  }

  // states
  sealed trait State
  case object Greeting extends State
  case object Helo extends State
  case object StartMessage extends State
  case object MailFrom extends State
  case object RcptTo extends State
  case object Data extends State
  case object Body extends State
  case object Rotate extends State
  case object Rset extends State
  case object Quit extends State
  case object Done extends State

  // data
  sealed trait StateData
  case object Uninitialized extends StateData
  final case class LastResponse(code: Int, level: Int, response: String, message: Option[String]) extends StateData

  // Protocol: messages received
  final case class ReceivedCommand(code: Int, level: Int, response: String)

}

class Handler(tcp: ActorRef) extends Actor with FSM[Handler.State, Handler.StateData] with ActorLogging {

  class BufferedTokenizer(delimiter: String = "\n") {

    import scala.collection.mutable.ListBuffer

    // Contains the data awaiting the next delimiter
    val input = new ListBuffer[String]

    def extract(data: String): Iterator[String] = {
      val entities = new ListBuffer[String] ++= data.split(delimiter, -1)
      input += entities.remove(0)
      if (entities.isEmpty) {
        Iterator.empty
      } else {
        entities.prepend(input.mkString)
        input.clear()
        input += entities.remove(entities.size - 1)
        entities.iterator
      }
    }

  }

  import Handler._

  val InitialGreeting = ByteString("220 localhost ESMTP net.jcain.net.MockSMTP")

  // Collects the raw response data and extracts the lines
  val rawBuffer = new BufferedTokenizer("\r\n")

  // Collects the processed response text line by line to accommodate continuation lines
  var responseBuffer: Vector[String] = Vector.empty

  tcp ! Tcp.Write(InitialGreeting)

  startWith(Greeting, Uninitialized)

  when (Greeting) {
    case Event(C_HELO(remoteHost), Uninitialized) =>
      goto(Helo)
    case Event(C_QUIT, Uninitialized) =>
      goto(Quit)
  }

  when (Helo) {
    case Event(C_HELO(remoteHost), Uninitialized) =>
      goto(MailFrom)
  }

  whenUnhandled {
    case Event(Tcp.Received(data), _) =>
      val str = data.utf8String
      for (line <- rawBuffer.extract(str)) {
        self ! createCommand(line)
      }
      stay()

    case Event(_: Tcp.ConnectionClosed, data @ LastResponse(responseCode, level, response, messageOpt)) =>
      log.info("Connection closed in state {}/{}", stateName, data)
      goto(Done)

    case Event(FSM.StateTimeout, _) =>
      goto(Done)

    case Event(e, s) =>
      log.warning("Received unhandled message {} in state {}/{}", e, stateName, s)
      stay()
  }

}