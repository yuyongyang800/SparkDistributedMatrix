package edu.purdue.dblab.apps

import edu.purdue.dblab.matrix.{SparseMatrix, DenseMatrix, Entry, LocalMatrix, BlockPartitionMatrix}
import helper.RankData
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by yongyangyu on 11/16/15.
  */
object EQTL {
  def main(args: Array[String]) {
    if (args.length < 6) {
      println("Usage: geno_matrix m1 n1 mrna_matrix m2 n2")
      System.exit(1)
    }
    val hdfs = "hdfs://openstack-vm-11-143.rcac.purdue.edu:8022/user/yu163/"//"hdfs://hathi-adm.rcac.purdue.edu:8020/user/yu163/"
    val matrixName1 = hdfs + args(0)
    val (m1, n1) = (args(1).toLong, args(2).toLong)
    val matrixName2 = hdfs + args(3)
    val (m2, n2) = (args(4).toLong, args(5).toLong)
    val conf = new SparkConf()
      .setAppName("eQTL")
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.shuffle.consolidateFiles", "true")
      .set("spark.shuffle.compress", "false")
      .set("spark.cores.max", "64")
      .set("spark.executor.memory", "6g")
      .set("spark.default.parallelism", "256")
      .set("spark.akka.frameSize", "64")
    conf.setJars(SparkContext.jarOfClass(this.getClass).toArray)
    val sc = new SparkContext(conf)
    val MrnaRDD = genMrnaRDD(sc, matrixName2)
    val mrnaSize = BlockPartitionMatrix.estimateBlockSizeWithDim(m2, n2)
    val mrnaRank = BlockPartitionMatrix.createFromCoordinateEntries(MrnaRDD, mrnaSize, mrnaSize, m2, n2)
    mrnaRank.blocks = mrnaRank.blocks.repartition(24)
    //println("mrnaRank")
    //println(mrnaRank.toLocalMatrix())
    val genoRDD = genGenoRDD(sc, matrixName1)
    //val genoSize = BlockPartitionMatrix.estimateBlockSizeWithDim(m1, n1)
    // try using the same block size for mrna matrix and geno matrix to avoid reblocking cost
    val geno = BlockPartitionMatrix.createFromCoordinateEntries(genoRDD, mrnaSize, mrnaSize, m1, n1)
    geno.blocks = geno.blocks.repartition(24)
    val I = new Array[BlockPartitionMatrix](3)
    for (i <- 0 until I.length) {
        //println(s"I($i) blocks: ")
        I(i) = genComponentOfI(geno, i)
        println(s"I($i) number of partitions: " + I(i).blocks.partitions.length)
        /*val arr = I(i).blocks.map {case ((i, j), mat) =>
            val num = mat match {
              case dm: DenseMatrix => dm.values.length
              case sp: SparseMatrix => sp.values.length
            }
          ((i, j), num)
        }.collect()
        for (elem <- arr) {
            println(elem._1 + ": " + elem._2)
        }*/
        //println(I(i).toLocalMatrix())
    }
    println("finish generating all I's ...")
    val N = new Array[BlockPartitionMatrix](3)
    for (i <- 0 until N.length) {
        N(i) = I(i).sumAlongRow()
        println(s"N($i) number of partitions: " + N(i).blocks.partitions.length)
        //println(s"N($i) blocks: ")
        /*val arr = N(i).blocks.map { case ((i, j), mat) =>
            val num = mat match {
              case dm: DenseMatrix => dm.values.length
              case sp: SparseMatrix => sp.values.length
            }
          ((i, j), num)
        }.collect()
        for (elem <- arr) {
            println(elem._1 + ": " + elem._2)
        }*/
        //println(N(i).toLocalMatrix())
    }
    println("finish computing N(i) ...")
    val Si = new Array[BlockPartitionMatrix](3)
    for (i <- 0 until Si.length) {
        Si(i) = (mrnaRank %*% (I(i).t)) ^ 2.0//mrnaRank %*% (I(i).t)
        println(s"Si($i) number of partitions: " + Si(i).blocks.partitions.length)
        /*println(s"Si($i) blocks: ")
        val arr = Si(i).blocks.map { case ((i, j), mat) =>
            val num = mat match {
              case dm: DenseMatrix => dm.values.length
              case sp: SparseMatrix => sp.values.length
            }
          ((i, j), num)
        }.collect()
        for (elem <- arr) {
            println(s"${elem._1}: ${elem._2}")
        }*/
        //println(Si(i).toLocalMatrix())
    }
    println("finish computing Si ...")
    val KK = geno.nCols()
    var S = Si(0).divideVector(N(0))//(Si(0) ^ 2.0).divideVector(N(0))
    println(s"S number of partitions: " + S.blocks.partitions.length)
    println("finish generating initial S ...")
    for (i <- 1 until 3) {
        //if (N(i).nnz() != 0) {
            //println(s"i=$i" + "*"*20)
            S = S + Si(i).divideVector(N(i))//S + (Si(i) ^ 2.0).divideVector(N(i))
            println(s"S number of partitions: " + S.blocks.partitions.length)
            /*println("S blocks")
            val arr = S.blocks.map { case ((i, j), mat) =>
                val num = mat match {
                  case dm: DenseMatrix => dm.values.length
                  case sp: SparseMatrix => sp.values.length
                }
              ((i, j), num)
            }.collect()
            for (elem <- arr) {
                println(s"${elem._1}: ${elem._2}")
            }*/
        //}
    }
    println("finish computing S ...")
    S = S * (12.0 / KK / (KK+1)) + (-3.0)*(KK+1)
    println(s"S number of partitions: " + S.blocks.partitions.length)
    // printing for test purpose
    //println(S.toLocalMatrix())
    println("saving files to HDFS ...")
    S.saveAsTextFile(hdfs + "tmp_result/eqtl")
    Thread.sleep(10000)
  }

  def genComponentOfI(geno:BlockPartitionMatrix, v: Double): BlockPartitionMatrix = {
      val RDD = geno.blocks.map { case ((i, j), mat) =>
        ((i, j), LocalMatrix.matrixEquals(mat, v))
      }
      new BlockPartitionMatrix(RDD, geno.ROWS_PER_BLK, geno.COLS_PER_BLK, geno.nRows(), geno.nCols())
  }

  def genMrnaRDD(sc: SparkContext, name: String): RDD[Entry] = {
      val lines = sc.textFile(name, 8)
      lines.map { line =>
          if (line.contains("Sample") || line.contains("HG") || line.contains("NA")) {
              Array(Entry(-1, -1, -1))
          }
          else {
              val row = line.split("\t")(0).toLong - 1
              val res = RankData.rankWithNoMissing(line)
              val arr = new Array[Entry](res.length)
              for (j <- 0 until arr.length) {
                  arr(j) = Entry(row, j, res(j))
              }
              arr
          }
      }.flatMap(x => x)
      .filter(x => x.row >= 0)
  }

  def genGenoRDD(sc: SparkContext, name: String): RDD[Entry] = {
      val lines = sc.textFile(name, 8)
      lines.map { line =>
          if (line.contains("Sample") || line.contains("HG") || line.contains("NA")) {
              Array(Entry(-1, -1, -1))
          }
          else {
              val strArr = line.split("\t")
              val row = strArr(0).toLong - 1
              val arr = new Array[Entry](strArr.length-1)
              for (j <- 0 until arr.length) {
                  if (strArr(j+1).equals("NaN") || strArr(j+1).toInt == -1) {
                      // randomly assign for missing data
                      //arr(j) = Entry(row, j, Random.nextInt(3))
                      // always assign missing data to 0, maybe better than randomly assigning
                      arr(j) = Entry(row, j, 0)
                  }
                  else {
                      arr(j) = Entry(row, j, strArr(j + 1).toDouble)
                  }
              }
            arr
          }
      }.flatMap(x => x)
      .filter(x => x.row >= 0)
  }
}
