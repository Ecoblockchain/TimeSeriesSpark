package edu.berkeley.cs.amplab.carat
import java.io.File

object PlotAndMakeAvailable extends App {
  val plotwww = "/mnt/www/plots"
  
  // rm -rf /mnt/www/plots/*.eps
  // rm -rf /mnt/www/plots/*/*.eps
  val f = new File(plotwww)
  val flist = f.listFiles
  for (k <- flist){
    if (k.isDirectory()){
      val klist = k.listFiles(new java.io.FilenameFilter() {
        def accept(dir: File, name: String) = name.endsWith(".eps")
      })
      for (j <- klist)
        j.delete()
    }else
      k.delete()
  }

  var master = "local[8]"
  if (args != null && args.length > 0) {
    master = args(0)
  }
  
  val plotDir = NoRDDPlots.main(Array(master, plotwww))
  // /mnt/www/treethumbnailer.sh /mnt/www/plots

  val temp = Runtime.getRuntime().exec(Array("/bin/bash", "/mnt/www/treethumbnailer.sh", plotwww))
  val err_read = new java.io.BufferedReader(new java.io.InputStreamReader(temp.getErrorStream()))
  val out_read = new java.io.BufferedReader(new java.io.InputStreamReader(temp.getInputStream()))
  var line = err_read.readLine()
  var line2 = out_read.readLine()
  while (line != null || line2 != null) {
    if (line != null){
      println(line)
      line = err_read.readLine()
    }
    
    if (line2 != null){
      println(line2)
      line2 = out_read.readLine()
    }
  }
  //temp.waitFor()
  sys.exit(0)
}