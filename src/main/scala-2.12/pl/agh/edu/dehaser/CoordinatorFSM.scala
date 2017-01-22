package pl.agh.edu.dehaser

import akka.actor.{ActorLogging, ActorPath, ActorRef, LoggingFSM, PoisonPill, Props}

import scala.concurrent.duration._

class CoordinatorFSM(alphabet: String, nrOfWorkers: Int, queuePath: ActorPath)
  extends LoggingFSM[CoordinatorState, CoordinatorData] with Dehash with ActorLogging {

  private val queue = context.actorSelection(queuePath)
  private val slaves = (1 to nrOfWorkers).map(_ => context.actorOf(DehashWorker.props(alphabet))).toSet


  startWith(Idle, Uninitialized)

  when(Idle, stateTimeout = 30 second) {
    case Event(DehashIt(hash, algo, originalSender), _) =>
      val wholeRange = BigRange(1, nrOfIterations(maxNrOfChars))
      val aggregator = context.actorOf(RangeAggregator.props(wholeRange, self))
      goto(Master) using ProcessData(subContractors = Set.empty[ActorRef],
        RangeConnector(), WorkDetails(hash, algo),
        wholeRange, BigRangeIterator(wholeRange),
        parent = originalSender, masterCoordinator = self, aggregator)

    case Event(AskHim(otherCoordinator), _) =>
      otherCoordinator ! GiveHalf
      stay()

    case Event(Invalid | StateTimeout, _) => goto(Idle)

    case Event(CheckHalf(range, details, master, aggregator), _) =>
      goto(ChunkProcessing) using ProcessData(subContractors = Set.empty[ActorRef],
        RangeConnector(), details, range, BigRangeIterator(range),
        parent = sender(), master, aggregator)
  }


  when(Master) {
    case Event(FoundIt(crackedPass), ProcessData(subContractors, _, _, _, _, client, _, aggregator)) =>
      client ! Cracked(crackedPass)
      subContractors.foreach(_ ! CancelComputaion)
      endMaster(aggregator)

    case Event(EverythingChecked, ProcessData(_, _, _, _, _, client, _, aggregator)) =>
      client ! NotFoundIt
      endMaster(aggregator)

    case Event(IamYourNewChild, data@ProcessData(subContractors, _, _, _, _, _, _, _)) =>
      stay() using data.copy(subContractors = subContractors + sender())

    case Event(rangeChecked@RangeChecked(range), data: ProcessData) =>
      val updatedRange = data.rangeConnector.addRange(range)
      data.aggregator ! rangeChecked
      checkedRange(rangeChecked, data, updatedRange)
    // todo go to some waiting state and wait for others to complete (when range connector will be full) after everything
    // TODO: master check every 60 second, if some ranges were't lost, and retransmits them into queue if needed
  }


  when(ChunkProcessing) {
    case Event(foundIt: FoundIt, ProcessData(subContractors, _, _, _, _, _, master, _)) =>
      master ! foundIt
      Leave(subContractors)

    case Event(ImLeaving, data@ProcessData(_, _, _, _, _, _, master, _)) =>
      master ! IamYourNewChild
      stay() using data.copy(parent = master)

    case Event(checked@RangeChecked(range), data@ProcessData(subContractors, rangeConnector, _,
    rangeToCheck, _, _, _, aggregator)) =>
      val updatedRange = rangeConnector.addRange(range)
      aggregator ! checked
      if (updatedRange.contains(rangeToCheck)) {
        // todo go to some waiting sate and wait for slaves to complete
        // todo [right now there are few unhandled messages from slaves]
        Leave(subContractors)
      } else checkedRange(checked, data, updatedRange)
  }


  onTransition {
    case _ -> Idle => queue ! GiveMeWork
    case Idle -> (Master | ChunkProcessing) =>
      slaves.foreach(_ ! WorkAvailable)
      queue ! OfferTask

    case (Master -> Master | ChunkProcessing -> ChunkProcessing) => nextStateData match {
      case ProcessData(_, _, _, range, _, _, _, _) =>
        if ((range.end - range.start) > splitThreshold) {
          queue ! OfferTask
        }
    }
  }


  whenUnhandled {
    case Event(GiveHalf, data@ProcessData(subContractorsCurrent, _, details, _, iterator, _, master, aggregator)) =>
      val optionalRanges = iterator.split()
      if (optionalRanges.isDefined) {
        val (first, second) = optionalRanges.get
        sender() ! CheckHalf(second, details, master, aggregator)
        goto(stateName) using data.copy(subContractors = subContractorsCurrent + sender(),
          iterator = BigRangeIterator(first), rangeToCheck = first)
      }
      else {
        sender() ! Invalid
        stay()
      }

    case Event(CancelComputaion, ProcessData(subContractors, _, _, _, _, _, _, _)) =>
      subContractors.foreach(_ ! CancelComputaion)
      goto(Idle) using Uninitialized


    case Event(GiveMeRange, data@ProcessData(_, _, details, _, iterator, _, _, _)) =>
      val (atom, iter) = iterator.next
      atom.foreach(x => sender() ! Check(x, details))
      goto(stateName) using data.copy(iterator = iter)

    case msg => log.error(s"unhandled msg:$msg")
      stay()
  }

  initialize()

  private def Leave(subContractors: Set[ActorRef]) = {
    subContractors.foreach(_ ! ImLeaving)
    goto(Idle) using Uninitialized
  }

  private def checkedRange(rangeChecked: RangeChecked, data: ProcessData, updatedRange: RangeConnector) = {
    val (atom, iter) = data.iterator.next
    atom.foreach(x => sender() ! Check(x, data.workDetails))
    stay() using data.copy(iterator = iter, rangeConnector = updatedRange)
  }

  private def endMaster(aggregator: ActorRef) = {
    aggregator ! PoisonPill
    goto(Idle) using Uninitialized
  }


  private def nrOfIterations(maxStringSize: Int): BigInt = {
    (1 to maxStringSize).map(x => BigInt(math.pow(alphabet.length, x).toLong)).sum
  }
}

object CoordinatorFSM {
  def props(alphabet: String, nrOfWorkers: Int = 4, queuePath: ActorPath): Props =
    Props(new CoordinatorFSM(alphabet, nrOfWorkers, queuePath))
}
