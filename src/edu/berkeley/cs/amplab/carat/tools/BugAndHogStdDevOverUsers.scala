package edu.berkeley.cs.amplab.carat.tools

import spark._
import spark.SparkContext._
import spark.timeseries._
import edu.berkeley.cs.amplab.carat._
import java.util.concurrent.Semaphore
import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.collection.immutable.Set
import scala.collection.immutable.HashSet
import scala.collection.immutable.TreeMap
import scala.collection.mutable.Map
import collection.JavaConversions._
import com.amazonaws.services.dynamodb.model.AttributeValue
import java.io.File
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import com.amazonaws.services.dynamodb.model.Key
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileInputStream
import java.io.FileWriter
import java.io.FileOutputStream
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoAnalysisUtil
import scala.collection.immutable.TreeSet
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoDbDecoder
import scala.actors.scheduler.ResizableThreadPoolScheduler
import scala.collection.mutable.HashMap
import com.esotericsoftware.kryo.Kryo

/**
 * Analyzes data in the Carat Amazon DynamoDb to obtain probability distributions
 * of battery rates for each of the following case pairs:
 * 1) App X is running & App X is not running
 * 2) App X is running on uuId U & App X is running on uuId != U
 * 3) OS Version == V & OS Version != V
 * 4) Device Model == M & Device Model != M
 * 5) uuId == U & uuId != U
 * 6) Similar apps to uuId U are running vs dissimilar apps are running.
 *    This is calculated by taking the set A of all apps ever reported running on uuId U
 *    and taking the data from samples where (A intersection sample.getAllApps()).size >= ln(A)
 *    and comparing it with (A intersection sample.getAllApps()).size < ln(A).
 *
 * Where uuId is a unique device identifier.
 *
 * NOTE: We do not store hogs or bugs with negative distance values.
 *
 * @author Eemil Lagerspetz
 */

object BugAndHogStdDevOverUsers {

  // Bucketing and decimal constants
  val buckets = 100
  val smallestBucket = 0.0001
  val DECIMALS = 3
  var DEBUG = false
  val LIMIT_SPEED = false
  val ABNORMAL_RATE = 9

  val tmpdir = "/mnt/TimeSeriesSpark-unstable/spark-temp-plots/"
  val RATES_CACHED_NEW = tmpdir + "cached-rates-new.dat"
  val RATES_CACHED = tmpdir + "cached-rates.dat"
  val LAST_SAMPLE = tmpdir + "last-sample.txt"
  val LAST_REG = tmpdir + "last-reg.txt"

  val last_sample = DynamoAnalysisUtil.readDoubleFromFile(LAST_SAMPLE)

  var last_sample_write = 0.0

  val last_reg = DynamoAnalysisUtil.readDoubleFromFile(LAST_REG)

  var last_reg_write = 0.0

  val dfs = "yyyy-MM-dd"
  val df = new SimpleDateFormat(dfs)
  val dateString = "plots-" + df.format(System.currentTimeMillis())

  val DATA_DIR = "data"
  val PLOTS = "plots"
  val PLOTFILES = "plotfiles"

  val Bug = "Bug"
  val Hog = "Hog"
  val Sim = "Sim"
  val Pro = "Pro"

  val BUGS = "bugs"
  val HOGS = "hogs"
  val SIM = "similarApps"
  val UUIDS = "uuIds"

  /**
   * Main program entry point.
   */
  def main(args: Array[String]) {
    var master = "local[1]"
    if (args != null && args.length >= 1) {
      master = args(0)
    }

    val start = DynamoAnalysisUtil.start()
    if (args != null && args.length > 1 && args(1) == "DEBUG") {
      DEBUG = true
    } else {
      // turn off INFO logging for spark:
      System.setProperty("hadoop.root.logger", "WARN,console")
      // This is misspelled in the spark jar log4j.properties:
      System.setProperty("log4j.threshhold", "WARN")
      // Include correct spelling to make sure
      System.setProperty("log4j.threshold", "WARN")
    }
    // turn on ProbUtil debug logging
    System.setProperty("log4j.category.spark.timeseries.ProbUtil.threshold", "DEBUG")
    System.setProperty("log4j.appender.spark.timeseries.ProbUtil.threshold", "DEBUG")

    // Fix Spark running out of space on AWS.
    System.setProperty("spark.local.dir", tmpdir)

    //System.setProperty("spark.kryo.registrator", classOf[CaratRateRegistrator].getName)
    val sc = TimeSeriesSpark.init(master, "default", "CaratDynamoDataToPlots")
    val allRates = DynamoAnalysisUtil.getRates(sc, tmpdir)
    analyzeRateData(sc, allRates, null)
    DynamoAnalysisUtil.replaceOldRateFile(RATES_CACHED, RATES_CACHED_NEW)
    DynamoAnalysisUtil.finish(start)
  }

  /**
   * Main analysis function. Called on the entire collected set of CaratRates.
   */
  def analyzeRateData(sc: SparkContext, inputRates: RDD[CaratRate], plotDirectory: String) = {
    // cache first
    val rates = inputRates.cache()

    // determine oses and models that appear in accepted data and use those
    val uuidToOsAndModel = new scala.collection.mutable.HashMap[String, (String, String)]
    uuidToOsAndModel ++= rates.map(x => { (x.uuid, (x.os, x.model)) }).collect()

    val oses = uuidToOsAndModel.map(_._2._1).toSet
    val models = uuidToOsAndModel.map(_._2._2).toSet

    println("uuIds with data: " + uuidToOsAndModel.keySet.mkString(", "))
    println("oses with data: " + oses.mkString(", "))
    println("models with data: " + models.mkString(", "))

    /**
     * uuid distributions, xmax, ev and evNeg
     * FIXME: With many users, this is a lot of data to keep in memory.
     * Consider changing the algorithm and using RDDs.
     */
    var distsWithUuid = new TreeMap[String, RDD[(Int, Double)]]
    var distsWithoutUuid = new TreeMap[String, RDD[(Int, Double)]]
    /* xmax, ev, evNeg */
    var parametersByUuid = new TreeMap[String, (Double, Double, Double)]
    /* evDistances*/
    var evDistanceByUuid = new TreeMap[String, Double]

    var appsByUuid = new TreeMap[String, Set[String]]

    println("Calculating aPriori.")
    val aPrioriDistribution = DynamoAnalysisUtil.getApriori(rates)
    println("Calculated aPriori.")
    if (aPrioriDistribution.size == 0)
      println("WARN: a priori dist is empty!")
    else
      println("a priori dist:\n" + aPrioriDistribution.mkString("\n"))

    var allApps = rates.flatMap(_.allApps).collect().toSet
    println("AllApps (with daemons): " + allApps)
    val DAEMONS_LIST_GLOBBED = DynamoAnalysisUtil.daemons_globbed(allApps)
    allApps --= DAEMONS_LIST_GLOBBED
    println("AllApps (no daemons): " + allApps)

    val uuidArray = uuidToOsAndModel.keySet.toArray.sortWith((s, t) => {
      s < t
    })
    // do all apps for an increasing number of users:
    for (users <- 1 until uuidArray.size + 1) {
      val uuids = uuidArray.slice(0, users)
      val uuidRates = rates.filter(x => { uuids.contains(x.uuid) })
      hogsAndBugs(sc, allApps, aPrioriDistribution, uuidRates, oses, models, uuids)
    }

    // return plot directory for caller
    dateString + "/" + PLOTS
  }

  def hogsAndBugs(sc: SparkContext, allApps: Set[String],
    aPrioriDistribution: Map[Double, Double], allRates: RDD[CaratRate],
    oses: Set[String], models: Set[String], uuidArray: Array[String]) = {
    println("Distances for all apps and %d users".format(uuidArray.size))
    /* Hogs: Consider all apps except daemons. */
    for (app <- allApps) {
      val filtered = allRates.filter(_.allApps.contains(app)).cache()

      val enoughWith = filtered.take(DynamoAnalysisUtil.DIST_THRESHOLD).length == DynamoAnalysisUtil.DIST_THRESHOLD
      
      getDistance("Hog " + app + " running", filtered, aPrioriDistribution, enoughWith)
      /**
       * FIXME: We still need the WITHOUT dist to get evDistance and whether this was a bug.
       */
      if (enoughWith) {
        // not a hog. is it a bug for anyone?
        for (i <- 0 until uuidArray.length) {
          val uuid = uuidArray(i)
          /* Bugs: Only consider apps reported from this uuId. Only consider apps not known to be hogs. */
          val appFromUuid = filtered.filter(_.uuid == uuid) //.cache()
          getDistance("Bug " + app + " running on client " + i, appFromUuid, aPrioriDistribution, enoughWith)
        }
      }
    }

    def getDistance(title: String,
      one: RDD[CaratRate], aPrioriDistribution: Map[Double, Double], enoughWith:Boolean) = {
      val (probDist, ev, usersWith) = DynamoAnalysisUtil.getEvAndDistribution(one, aPrioriDistribution, enoughWith)
      if (probDist != null) {
        printf("%s (%s users) evWith=%s\n", title, usersWith, ev)
      }
    }
  }
}
