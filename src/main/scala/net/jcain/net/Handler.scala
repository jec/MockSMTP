package net.jcain.net

import akka.actor.{Actor, ActorLogging, ActorRef, FSM}
import akka.io.Tcp
import akka.util.ByteString
import scala.collection.immutable.Queue

object Handler {

  val InitialGreeting = ByteString("220 localhost ESMTP net.jcain.net.MockSMTP\r\n")
  val CommandNotRecognized = ByteString("500 Error: command not recognized\r\n")
  val OK = ByteString("250 Ok\r\n")
  val NoRelay = ByteString("554 Relay access denied\r\n")
  val NotFound = ByteString("550 User not found\r\n")
  val NoPtr = ByteString("550 No PTR record found\r\n")
  val EndWithCrLf = ByteString("354 End data with <CR><LF>.<CR><LF>\r\n")
  val Bye = ByteString("221 Bye\r\n")

  object RcptPatterns {
    val NotFound = "NOTFOUND".r.unanchored
    val NoRelay = "NORELAY".r.unanchored
  }

  object BodyPatterns {
    val NoPtr = "_NOPTR_".r.unanchored
  }

  // SMTP commands
  sealed trait Command
  object C_HELO { val Pattern = """(HELO|helo) +(.*)""".r }
  final case class C_HELO(host: String) extends Command
  object C_EHLO { val Pattern = """(EHLO|ehlo) +(.*)""".r }
  final case class C_EHLO(host: String) extends Command
  object C_MAIL { val Pattern = """(MAIL FROM|mail from):<(.*)>""".r }
  final case class C_MAIL(from: String) extends Command
  object C_RCPT { val Pattern = """(RCPT TO|rcpt to):<(.*)>""".r }
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
  case object Idle extends State
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
  final case class HeloData(remoteHost: String) extends StateData
  final case class Pending(remoteHost: String, from: String, rcpts: Queue[String] = Queue(), body: StringBuilder = new StringBuilder) extends StateData

  // Protocol: messages received
  final case class ReceivedCommand(code: Int, level: Int, response: String)

}

class Handler(tcp: ActorRef, initialGreeting: Option[String] = None)
extends Actor with FSM[Handler.State, Handler.StateData] with ActorLogging {

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

  // Collects the raw response data and extracts the lines
  val rawBuffer = new BufferedTokenizer("\r\n")

  // Collects the processed response text line by line to accommodate continuation lines
  var responseBuffer: Vector[String] = Vector.empty

  startWith(Greeting, Uninitialized)

  initialGreeting match {
    case None =>
      sendData(InitialGreeting)
    case Some(greeting) =>
      sendData(greeting)
      tcp ! Tcp.Close
      context.stop(self)
  }

  when (Greeting) {
    case Event(C_HELO(remoteHost), Uninitialized) =>
      sendData(s"250 Hello $remoteHost, nice to meet you")
      goto(Idle) using HeloData(remoteHost)
    case Event(C_EHLO(remoteHost), Uninitialized) =>
      sendData("250 localhost") // TODO: add extensions?
      goto(Idle) using HeloData(remoteHost)
    case Event(C_QUIT, Uninitialized) =>
      goto(Quit)
  }

  when (Idle) {
    case Event(C_MAIL(from), HeloData(remoteHost)) =>
      sendData(OK)
      goto(MailFrom) using Pending(remoteHost, from)
  }

  when (MailFrom) {
    case Event(C_RCPT(to), Pending(remoteHost, from, rcpts, body)) =>
      if (validateRecipient(to))
        goto(RcptTo) using Pending(remoteHost, from, Queue(to), body)
      else
        stay()
  }

  when (RcptTo) {
    case Event(C_RCPT(to), Pending(remoteHost, from, rcpts, body)) =>
      val newRcpts = if (validateRecipient(to))
        rcpts.enqueue(to)
      else
        rcpts
      stay() using Pending(remoteHost, from, newRcpts, body)
    case Event(C_DATA, pending: Pending) =>
      sendData(EndWithCrLf)
      goto(Data)
  }

  when (Data) {
    case Event(Tcp.Received(data), pending @ Pending(remoteHost, from, rcpts, body)) =>
      val newBody = body.append(data.utf8String)
      if (newBody.endsWith(Seq(13, 10, 46, 13, 10)))
        validateMessage(newBody.toString, pending)
      else
        stay() using Pending(remoteHost, from, rcpts, newBody)
  }

  whenUnhandled {
    case Event(Tcp.Received(data), _) =>
      val str = data.utf8String
      for (line <- rawBuffer.extract(str)) {
        createCommand(line.stripLineEnd) match {
          case Some(cmd) => self ! cmd
          case None      => sendData(CommandNotRecognized)
        }
      }
      stay()

    case Event(C_QUIT, _) =>
      quit()

    case Event(C_RSET, HeloData(remoteHost)) =>
      sendData(OK)
      stay()

    case Event(C_RSET, Pending(remoteHost, from, rcpts, body)) =>
      sendData(OK)
      goto(Idle) using HeloData(remoteHost)

    case Event(_: Tcp.ConnectionClosed, data: StateData) =>
      log.info("Connection closed in state {}/{}", stateName, data)
      quit()

    case Event(e, s) =>
      log.warning("Received unhandled message {} in state {}/{}", e, stateName, s)
      stay()
  }

  def validateRecipient(rcpt: String) =
    rcpt match {
      case RcptPatterns.NoRelay(_*) =>
        sendData(NoRelay)
        false
      case RcptPatterns.NotFound(_*) =>
        sendData(NotFound)
        false
      case _ =>
        sendData(OK)
        true
    }

  def validateMessage(body: String, pending: Pending) =
    body match {
      case BodyPatterns.NoPtr(_*) =>
        quit(NoPtr)
      case _ =>
        // TODO: store the message
        sendData("250 Ok: queued as 12345")
        goto(Idle) using HeloData(pending.remoteHost)
    }

  def quit(message: ByteString = Bye) = {
    sendData(message)
    tcp ! Tcp.Close
    context.stop(self)
    stay()
  }

  def sendData(text: String) =
    tcp ! Tcp.Write(ByteString(s"$text\r\n"))

  def sendData(data: ByteString) =
    tcp ! Tcp.Write(data)

}
