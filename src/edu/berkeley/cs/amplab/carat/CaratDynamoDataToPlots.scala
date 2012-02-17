package edu.berkeley.cs.amplab.carat

import spark._
import spark.SparkContext._
import spark.timeseries._
import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.collection.immutable.Set
import scala.collection.immutable.HashSet
import scala.collection.immutable.TreeMap
import collection.JavaConversions._
import com.amazonaws.services.dynamodb.model.AttributeValue
import java.io.File
import java.text.SimpleDateFormat

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
 * @author Eemil Lagerspetz
 */

object CaratDynamoDataToPlots {
  /**
   * We do not store hogs or bugs with negative distance values.
   */

  // Bucketing and decimal constants
  val buckets = 100
  val smallestBucket = 0.0001
  val DECIMALS = 3
  var DEBUG = false
  val LIMIT_SPEED = false
  val ABNORMAL_RATE = 9
  
  val dfs = "yyyy-MM-dd"
  val df = new SimpleDateFormat(dfs)
  val dateString = "plots-"+df.format(System.currentTimeMillis())

  /**
   * Main program entry point.
   */
  def main(args: Array[String]) {
    var master = "local[1]"
    if (args != null && args.length >= 1) {
      master = args(0)
      if (args.length > 1 && args(1) == "DEBUG")
        DEBUG = true
    }

    val sc = new SparkContext(master, "CaratDynamoDataAnalysis")
    analyzeData(sc)
    sys.exit(0)
  }

  /**
   * Main function. Called from main() after sc initialization.
   */

  def analyzeData(sc: SparkContext) {
    // Unique uuIds, Oses, and Models from registrations.
    val allUuids = new scala.collection.mutable.HashSet[String]
    val allModels = new scala.collection.mutable.HashSet[String]
    val allOses = new scala.collection.mutable.HashSet[String]

    // Master RDD for all data.
    var allRates: spark.RDD[CaratRate] = null

    allRates = CaratDynamoDataAnalysis.DynamoDbItemLoop(DynamoDbDecoder.getAllItems(registrationTable),
      DynamoDbDecoder.getAllItems(registrationTable, _),
      CaratDynamoDataAnalysis.handleRegs(sc, _, _, allUuids, allOses, allModels), false, allRates)

    println("All uuIds: " + allUuids.mkString(", "))
    println("All oses: " + allOses.mkString(", "))
    println("All models: " + allModels.mkString(", "))

    if (allRates != null) {
      analyzeRateData(allRates, allUuids, allOses, allModels)
    }
  }
  
  /**
   * Main analysis function. Called on the entire collected set of CaratRates.
   */
  def analyzeRateData(allRates: RDD[CaratRate],
    uuids: scala.collection.mutable.Set[String], oses: scala.collection.mutable.Set[String], models: scala.collection.mutable.Set[String]) {
    /* Daemon apps, hardcoded for now */
    var daemons: Set[String] = Set(
      "aggregated",
      "apsd",
      "BTServer",
      "Carat",
      "configd",
      "calaccessd",
      "dataaccessd",
      "fseventsd",
      "iapd",
      "imagent",
      "installd",
      "kernel_task",
      "launchd",
      "librariand",
      "locationd",
      "lockdownd",
      "lsd",
      "mDNSResponder",
      "mediaremoted",
      "mediaserverd",
      "MobileMail",
      "MobilePhone",
      "MobileSafari",
      "networkd",
      "notifyd",
      "pasteboardd",
      "powerd",
      "sandboxd",
      "securityd",
      "SpringBoard",
      "syslogd",
      "ubd",
      "UserEventAgent",
      "wifid",
      "WindowServer", "dynamic_pager", "logind", "fontd", 
      "warmd", "coreservicesd", "autofsd", "warmd_agent",
      "filecoordination", "mds", "hidd", "kextd", "diskarbitrationd",
      "mdworker")

    /**
     * uuid distributions, xmax, ev and evNeg
     */
    var distsWithUuid = new TreeMap[String, TreeMap[Int, Double]]
    var distsWithoutUuid = new TreeMap[String, TreeMap[Int, Double]]
    /* xmax, ev, evNeg */
    var parametersByUuid = new TreeMap[String, (Double, Double, Double)]
    /* evDistances*/
    var evDistanceByUuid = new TreeMap[String, Double]
    
    var appsByUuid = new TreeMap[String, scala.collection.mutable.HashSet[String]]
    /*if (DEBUG) {
      val cc = allRates.collect()
      for (k <- cc)
        println(k)
    }*/

    val apps = allRates.map(x => {
      var sampleApps = x.allApps
      sampleApps --= daemons
      sampleApps
    }).collect()

    var allApps = new HashSet[String]
    for (k <- apps)
      allApps ++= k
      
    // mediaremoted does not get removed here, why?
    println("AllApps (no daemons): " + allApps)

    for (os <- oses) {
      val fromOs = allRates.filter(_.os == os)
      val notFromOs = allRates.filter(_.os != os)
      // no distance check, not bug or hog
      plotDists("iOs " + os, "Other versions", fromOs, notFromOs, false)
    }

    for (model <- models) {
      val fromModel = allRates.filter(_.model == model)
      val notFromModel = allRates.filter(_.model != model)
      // no distance check, not bug or hog
      plotDists(model, "Other models", fromModel, notFromModel, false)
    }

    var allHogs = new HashSet[String]
    /* Hogs: Consider all apps except daemons. */
    for (app <- allApps) {
      if (app != CARAT) {
        val filtered = allRates.filter(_.allApps.contains(app))
        val filteredNeg = allRates.filter(!_.allApps.contains(app))
        if (plotDists("Hog " + app, "Other apps", filtered, filteredNeg, true)) {
          // this is a hog
          allHogs += app
        }
      }
    }

    var intersectEverReportedApps = new scala.collection.mutable.HashSet[String]
    var intersectPerSampleApps = new scala.collection.mutable.HashSet[String]

    for (uuid <- uuids) {
      val fromUuid = allRates.filter(_.uuid == uuid)

      val tempApps = fromUuid.map(x => {
      var sampleApps = x.allApps
      sampleApps --= daemons
      sampleApps --= allHogs
      sampleApps
      }).collect()

      var uuidApps = new scala.collection.mutable.HashSet[String]

      // Get all apps ever reported, also compute likely daemons
      for (k <- tempApps) {
        uuidApps ++= k
        if (intersectPerSampleApps.size == 0)
          intersectPerSampleApps ++= k
        else if (k.size > 0)
          intersectPerSampleApps = intersectPerSampleApps.intersect(k)
      }

      //Another method to find likely daemons
      if (intersectEverReportedApps.size == 0)
        intersectEverReportedApps = uuidApps
      else if (uuidApps.size > 0)
        intersectEverReportedApps = intersectEverReportedApps.intersect(uuidApps)

      if (uuidApps.size > 0)
        similarApps(allRates, uuid, uuidApps)
      //else
      // Remove similar apps entry?

      val notFromUuid = allRates.filter(_.uuid != uuid)
      // no distance check, not bug or hog
      val (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance) = getDistanceAndDistributions(fromUuid, notFromUuid)
      if (bucketed != null && bucketedNeg != null) {
        distsWithUuid += ((uuid, bucketed))
        distsWithoutUuid += ((uuid, bucketedNeg))
        parametersByUuid += ((uuid, (xmax, ev, evNeg)))
        evDistanceByUuid += ((uuid, evDistance))
      }
      appsByUuid += ((uuid, uuidApps))

      /* Bugs: Only consider apps reported from this uuId. Only consider apps not known to be hogs. */
      for (app <- uuidApps) {
        if (app != CARAT) {
          val appFromUuid = fromUuid.filter(_.allApps.contains(app))
          val appNotFromUuid = notFromUuid.filter(_.allApps.contains(app))
          plotDists("Bug "+app + " on " + uuid, app + " elsewhere", appFromUuid, appNotFromUuid, true)
        }
      }
    }
    
    plotJScores(distsWithUuid, distsWithoutUuid, parametersByUuid, evDistanceByUuid, appsByUuid)
    
    val removed = daemons -- intersectEverReportedApps
    val removedPS = daemons -- intersectPerSampleApps
    intersectEverReportedApps --= daemons
    intersectPerSampleApps --= daemons
    println("Daemons: " + daemons)
    if (intersectEverReportedApps.size > 0)
      println("New possible daemons (ever reported): " + intersectEverReportedApps)
    if (intersectPerSampleApps.size > 0)
      println("New possible daemons (per sample): " + intersectPerSampleApps)
    if (removed.size > 0)
      println("Removed daemons (ever reported): " + removed)
    if (removedPS.size > 0)
      println("Removed daemons (per sample): " + removedPS)
  }

  /**
   * Calculate similar apps for device `uuid` based on all rate measurements and apps reported on the device.
   * Write them to DynamoDb.
   */
  def similarApps(all: RDD[CaratRate], uuid: String, uuidApps: scala.collection.mutable.Set[String]) {
    val sCount = similarityCount(uuidApps.size)
    printf("SimilarApps uuid=%s sCount=%s uuidApps.size=%s\n", uuid, sCount, uuidApps.size)
    val similar = all.filter(_.allApps.intersect(uuidApps).size >= sCount)
    val dissimilar = all.filter(_.allApps.intersect(uuidApps).size < sCount)
    //printf("SimilarApps similar.count=%s dissimilar.count=%s\n",similar.count(), dissimilar.count())
    // no distance check, not bug or hog
    plotDists("Similar users with " + uuid, "Dissimilar users", similar, dissimilar, false)
  }

  def getDistanceAndDistributions(one: RDD[CaratRate], two: RDD[CaratRate]) = {

    // probability distribution: r, count/sumCount

    /* Figure out max x value (maximum rate) and bucket y values of 
       * both distributions into n buckets, averaging inside a bucket
       */

    val flatOne = one.map(x => {
      if (x.isUniform())
        x.rateRange
      else
        new UniformDist(x.rate, x.rate)
    }).collect()
    val flatTwo = two.map(x => {
      if (x.isUniform())
        x.rateRange
      else
        new UniformDist(x.rate, x.rate)
    }).collect()

    var evDistance = 0.0

    if (flatOne.size > 0 && flatTwo.size > 0) {
      println("rates=" + flatOne.size + " ratesNeg=" + flatTwo.size)
      if (flatOne.size < 10) {
        println("Less than 10 rates in \"with\": " + flatOne.mkString("\n"))
      }

      if (flatTwo.size < 10) {
        println("Less than 10 rates in \"without\": " + flatTwo.mkString("\n"))
      }

      if (DEBUG) {
        ProbUtil.debugNonZero(flatOne.map(_.getEv), flatTwo.map(_.getEv), "rates")
      }

      val (xmax, bucketed, bucketedNeg, ev, evNeg) = ProbUtil.logBucketDistributionsByX(flatOne, flatTwo, buckets, smallestBucket, DECIMALS)

      evDistance = CaratDynamoDataAnalysis.evDiff(ev, evNeg)
      printf("evWith=%s evWithout=%s evDistance=%s\n", ev, evNeg, evDistance)

      (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance)
    } else
      (0.0, null, null, 0.0, 0.0, 0.0)
  }

  /* TODO: Generate a gnuplot-readable plot file of the bucketed distribution.
   * Create folders plots/data plots/plotfiles
   * Save it as "plots/data/titleWith-titleWithout".txt.
   * Also generate a plotfile called plots/plotfiles/titleWith-titleWithout.gnuplot
   */
  def plotDists(title: String, titleNeg: String, one: RDD[CaratRate], two: RDD[CaratRate], isBugOrHog: Boolean) = {
    val (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance) = getDistanceAndDistributions(one, two)

    if (bucketed != null && bucketedNeg != null) {
      plot(title, titleNeg, xmax, bucketed, bucketedNeg, ev, evNeg, evDistance)
    }
    isBugOrHog && evDistance > 0
  }

   /**
     * The J-Score is the % of people with worse = higher energy use.
     * therefore, it is the size of the set of evDistances that are higher than mine,
     * compared to the size of the user base.
     * Note that the server side multiplies the JScore by 100, and we store it here
     * as a fraction.
     */
  def plotJScores(distsWithUuid: TreeMap[String, TreeMap[Int, Double]],
    distsWithoutUuid: TreeMap[String, TreeMap[Int, Double]],
    parametersByUuid: TreeMap[String, (Double, Double, Double)],
    evDistanceByUuid: TreeMap[String, Double],
    appsByUuid: TreeMap[String, scala.collection.mutable.HashSet[String]]) {
    val dists = evDistanceByUuid.map(_._2).toSeq.sorted

    for (k <- distsWithUuid.keys) {
      val (xmax, ev, evNeg) = parametersByUuid.get(k).getOrElse((0.0, 0.0, 0.0))
      
      /**
       * jscore is the % of people with worse = higher energy use.
       * therefore, it is the size of the set of evDistances that are higher than mine,
       * compared to the size of the user base.
       */
      val jscore = {
        val temp = evDistanceByUuid.get(k).getOrElse(0.0)
        if (temp == 0)
          0
        else
          ProbUtil.nDecimal(dists.filter(_ > temp).size*1.0 / dists.size, DECIMALS)
      }
      val distWith = distsWithUuid.get(k).getOrElse(null)
      val distWithout = distsWithoutUuid.get(k).getOrElse(null)
      val apps = appsByUuid.get(k).getOrElse(null)
      if (distWith != null && distWithout != null && apps != null)
        plot("Profile for " + k, "Other users", xmax, distWith, distWithout, ev, evNeg, jscore, apps.toSeq)
      else
        printf("Error: Could not plot jscore, because: distWith=%s distWithout=%s apps=%s\n", distWith, distWithout, apps)
    }
  }
  
  def plot(title: String, titleNeg: String, xmax:Double,
      distWith: TreeMap[Int, Double], distWithout: TreeMap[Int, Double],
      ev:Double, evNeg:Double, evDistance:Double, apps: Seq[String] = null) {
    printf("Plotting %s vs %s, distance=%s, evWith=%s evWithout=%s\n", title, titleNeg, evDistance, ev, evNeg)
    plotFile(dateString, title, title, titleNeg)
    writeData(dateString, title, distWith, xmax)
    writeData(dateString, titleNeg, distWithout, xmax)
    plotData(dateString, title)
  }
  
  val DATA_DIR = "data"
  val PLOTS = "plots"
  val PLOTFILES = "plotfiles"

  def plotFile(dir: String, name: String, t1: String, t2: String) = {
    val pdir = dir + "/" + PLOTS + "/"
    val gdir = dir + "/" + PLOTFILES + "/"
    val ddir = dir + "/" + DATA_DIR + "/"
    var f = new File(pdir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      f = new File(gdir)
      if (!f.isDirectory() && !f.mkdirs())
        println("Failed to create " + f + " for plots!")
      else {
        f = new File(ddir)
        if (!f.isDirectory() && !f.mkdirs())
          println("Failed to create " + f + " for plots!")
        else {
          val plotfile = new java.io.FileWriter(gdir + name + ".gnuplot")
          plotfile.write("set term postscript eps enhanced color 'Arial' 24\nset xtics out\n" +
            "set size 1.93,1.1\n" +
            "set logscale x\n" +
            "set xlabel \"Battery drain % / s\"\n" +
            "set ylabel \"Probability\"\n")
          plotfile.write("set output \"" + pdir + name + ".eps\"\n")
          plotfile.write("plot \"" + ddir + t1 + ".txt\" using 1:2 with linespoints lt rgb \"#f3b14d\" lw 2 title \"" + t1 + "\", " +
            "\"" + ddir + t2 + ".txt\" using 1:2 with linespoints lt rgb \"#007777\" lw 2 title \"" + t2 + "\"\n")
          plotfile.close
          true
        }
      }
    }
  }
  
  def writeData(dir:String, name:String, dist: TreeMap[Int, Double], xmax:Double){
    val logbase = ProbUtil.getLogBase(buckets, smallestBucket, xmax)
    val ddir = dir + "/" + DATA_DIR + "/"
    var f = new File(ddir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      val datafile = new java.io.FileWriter(ddir + name + ".txt")

      val data = dist.map(x => {
        val bucketStart = {
          if (x._1 == 0)
            0.0
          else
            xmax / (math.pow(logbase, buckets - x._1))
        }
        val bucketEnd = xmax / (math.pow(logbase, buckets - x._1 - 1))
        
        (bucketStart+bucketEnd)/2 +" "+ x._2
      })
      
      for (k <- data)
        datafile.write(k +"\n")
      datafile.close
    }
  }

  def plotData(dir: String, title: String) {
    val gdir = dir + "/" + PLOTFILES + "/"
    val f = new File(gdir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      val temp = Runtime.getRuntime().exec("gnuplot \"" + gdir + title + ".gnuplot\"")
      val err_read = new java.io.BufferedReader(new java.io.InputStreamReader(temp.getErrorStream()))
      var line = err_read.readLine()
      while (line != null) {
        println(line)
        line = err_read.readLine()
      }
    }
  }
}