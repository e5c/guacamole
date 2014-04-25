package org.bdgenomics.guacamole

import org.bdgenomics.adam.avro.ADAMRecord
import scala.collection.mutable
import org.bdgenomics.adam.rich.{ DecadentRead, RichADAMRecord }
import org.apache.spark.Logging

/**
 * Suppose we have a set of loci on a given contig and some reads that are mapped to that contig, and at each locus we
 * want to look at the reads that overlap a window of a certain number of bases surrounding that locus. This class
 * implements this "sliding window" functionality.
 *
 * After instantiating this class, call [[SlidingReadWindow.setCurrentLocus( )]] repeatedly for each
 * locus being considered. After calling this method, the [[SlidingReadWindow.currentReads]] property will contain the
 * reads that overlap the current locus.
 *
 * To enable an efficient implementation, we require that both the sequence of loci to be considered and the iterator
 * of reads are sorted.
 *
 * @param halfWindowSize Number of nucleotide bases to either side of the specified locus to provide reads for. For
 *                       example, if halfWindowSize=5, and our currentLocus=100, then currentReads will include reads
 *                       that map to anywhere between 95 and 105, inclusive. Set to 0 to consider only reads that
 *                       overlap the exact locus being considered, with no surrounding window.
 *
 * @param rawSortedReads Iterator of aligned reads, sorted by the aligned start locus.
 */
case class SlidingReadWindow(halfWindowSize: Long, rawSortedReads: Iterator[ADAMRecord]) extends Logging {
  /** The locus currently under consideration. */
  var currentLocus = -1L

  private var referenceName: Option[String] = None
  private var mostRecentReadStart: Long = 0
  private val sortedReads: Iterator[DecadentRead] = rawSortedReads.map(read => {
    require(read.getReadMapped, "Reads must be mapped")
    if (referenceName.isEmpty) referenceName = Some(read.contig.contigName.toString)
    require(read.contig.contigName.toString == referenceName.get, "Reads must have the same reference name")
    require(read.getStart >= mostRecentReadStart, "Reads must be sorted by start locus")
    require(read.getCigar.length > 1, "Reads must have a CIGAR string")
    DecadentRead(read)
  })
  private val currentReadsPriorityQueue = {
    // Order reads by end locus, increasing.
    def readOrdering = new Ordering[DecadentRead] {
      def compare(first: DecadentRead, second: DecadentRead) = second.record.end.get.compare(first.record.end.get)
    }
    new mutable.PriorityQueue[DecadentRead]()(readOrdering)
  }

  /** The reads that overlap the window surrounding [[currentLocus]]. */
  def currentReads(): Seq[DecadentRead] = {
    currentReadsPriorityQueue.toSeq
  }

  /**
   * Advance to the specified locus, which must be greater than the current locus. After calling this, the
   * [[currentReads]] method will give the overlapping reads at the new locus.
   *
   * @param locus Locus to advance to.
   * @return An iterator over the *new reads* that were added as a result of this call. Note that this is not the full
   *         set of reads in the window: you must examine [[currentReads]] for that.
   */
  def setCurrentLocus(locus: Long): Seq[DecadentRead] = {
    assume(locus >= currentLocus, "Pileup window can only move forward in locus")

    def overlaps(read: DecadentRead) = {
      read.record.start <= locus + halfWindowSize && read.record.end.get >= locus - halfWindowSize
    }

    // Remove reads that are no longer in the window.
    while (!currentReadsPriorityQueue.isEmpty && currentReadsPriorityQueue.head.record.end.get < locus - halfWindowSize) {
      val dropped = currentReadsPriorityQueue.dequeue()
      assert(!overlaps(dropped))
    }
    // Add new reads that are now in the window.
    val newReads = sortedReads.takeWhile(_.record.start <= locus + halfWindowSize).filter(overlaps).toSeq
    currentReadsPriorityQueue.enqueue(newReads: _*)
    assert(currentReadsPriorityQueue.forall(overlaps)) // Correctness check.
    if (currentReadsPriorityQueue.isEmpty) {
      log.warn("No reads overlap locus %d with half window size %d.".format(locus, halfWindowSize))
      if (!sortedReads.hasNext) log.warn("Iterator of sorted reads is empty.")
    }
    currentLocus = locus
    newReads // We return the newly added reads.
  }
}