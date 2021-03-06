package com.spark_helper

import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.FileUtil
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.LongWritable
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapred.lib.MultipleTextOutputFormat
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat

import scala.util.Random

/** A facility to deal with RDD/file manipulations based on the Spark API.
  *
  * The goal is to remove the maximum of highly used low-level code from your
  * spark job and replace it with methods fully tested whose name is
  * self-explanatory/readable.
  *
  * A few exemples:
  *
  * {{{
  * // Same as SparkContext.saveAsTextFile, but the result is a single file:
  * SparkHelper.saveAsSingleTextFile(myOutputRDD, "/my/output/file/path.txt")
  * // Same as SparkContext.textFile, but instead of reading one record per line,
  * // it reads records spread over several lines:
  * SparkHelper.textFileWithDelimiter("/my/input/folder/path", sparkContext, "---\n")
  * }}}
  *
  * Source <a href="https://github.com/xavierguihot/spark_helper/blob/master/src
  * /main/scala/com/spark_helper/SparkHelper.scala">SparkHelper</a>
  *
  * @author Xavier Guihot
  * @since 2017-02
  */
object SparkHelper extends Serializable {

	/** Saves an RDD in exactly one file.
	  *
	  * Allows one to save an RDD in one file, while keeping the processing
	  * parallelized.
	  *
	  * {{{ SparkHelper.saveAsSingleTextFile(myRddToStore, "/my/file/path.txt") }}}
	  *
	  * @param outputRDD the RDD of strings to store in one file
	  * @param outputFile the path of the produced file
	  */
	def saveAsSingleTextFile(outputRDD: RDD[String], outputFile: String) = {
		saveAsSingleTextFileInternal(outputRDD, outputFile, None)
	}

	/** Saves an RDD in exactly one file.
	  *
	  * Allows one to save an RDD in one file, while keeping the processing
	  * parallelized.
	  *
	  * {{{
	  * SparkHelper.saveAsSingleTextFile(myRddToStore, "/my/file/path.txt", classOf[BZip2Codec])
	  * }}}
	  *
	  * @param outputRDD the RDD of strings to store in one file
	  * @param outputFile the path of the produced file
	  * @param compressionCodec the type of compression to use (for instance
	  * classOf[BZip2Codec] or classOf[GzipCodec]))
	  */
	def saveAsSingleTextFile(
		outputRDD: RDD[String], outputFile: String,
		compressionCodec: Class[_ <: CompressionCodec]
	) = {
		saveAsSingleTextFileInternal(outputRDD, outputFile, Some(compressionCodec))
	}

	/** Saves an RDD in exactly one file.
	  *
	  * Allows one to save an RDD in one file, while keeping the processing
	  * parallelized.
	  *
	  * This variant of saveAsSingleTextFile performs the storage in a temporary
	  * folder instead of directly in the final output folder. This way the
	  * risks of having corrupted files in the real output folder due to cluster
	  * interruptions is minimized.
	  *
	  * {{{
	  * SparkHelper.saveAsSingleTextFile(myRddToStore, "/my/file/path.txt", "/my/working/folder/path")
	  * }}}
	  *
	  * @param outputRDD the RDD of strings to store in one file
	  * @param outputFile the path of the produced file
	  * @param workingFolder the path where file manipulations will temporarily
	  * happen.
	  */
	def saveAsSingleTextFile(
		outputRDD: RDD[String], outputFile: String, workingFolder: String
	) = {
		saveAsSingleTextFileWithWorkingFolderInternal(
			outputRDD, outputFile, workingFolder, None
		)
	}

	/** Saves an RDD in exactly one file.
	  *
	  * Allows one to save an RDD in one file, while keeping the processing
	  * parallelized.
	  *
	  * This variant of saveAsSingleTextFile performs the storage in a temporary
	  * folder instead of directly in the final output folder. This way the
	  * risks of having corrupted files in the real output folder due to cluster
	  * interruptions is minimized.
	  *
	  * {{{
	  * SparkHelper.saveAsSingleTextFile(myRddToStore, "/my/file/path.txt", "/my/working/folder/path", classOf[BZip2Codec])
	  * }}}
	  *
	  * @param outputRDD the RDD of strings to store in one file
	  * @param outputFile the path of the produced file
	  * @param workingFolder the path where file manipulations will temporarily
	  * happen.
	  * @param compressionCodec the type of compression to use (for instance
	  * classOf[BZip2Codec] or classOf[GzipCodec]))
	  */
	def saveAsSingleTextFile(
		outputRDD: RDD[String], outputFile: String, workingFolder: String,
		compressionCodec: Class[_ <: CompressionCodec]
	) = {
		saveAsSingleTextFileWithWorkingFolderInternal(
			outputRDD, outputFile, workingFolder, Some(compressionCodec)
		)
	}

	/** Equivalent to sparkContext.textFile(), but for a specific record delimiter.
	  *
	  * By default, sparkContext.textFile() will provide one record per line.
	  * But what if the format you want to read considers that one record (one
	  * entity) is stored in more than one line (yml, xml, ...)?
	  *
	  * For instance in order to read a yml file, which is a format for which a
	  * record (a single entity) is spread other several lines, you can modify
	  * the record delimiter with "---\n" instead of "\n". Same goes when
	  * reading an xml file where a record might be spread over several lines or
	  * worse the whole xml file is one line.
	  *
	  * {{{
	  * // Let's say data we want to use with Spark looks like this (one record
	  * // is a customer, but it's spread over several lines):
	  * <Customers>\n
	  * <Customer>\n
	  * <Address>34 thingy street, someplace, sometown</Address>\n
	  * </Customer>\n
	  * <Customer>\n
	  * <Address>12 thingy street, someplace, sometown</Address>\n
	  * </Customer>\n
	  * </Customers>
	  * //Then you can use it this way:
	  * val computedRecords = HdfsHelper.textFileWithDelimiter(
	  * 	"my/path/to/customers.xml", sparkContext, <Customer>\n
	  * ).collect()
	  * val expectedRecords = Array(
	  * 	<Customers>\n,
	  * 	(
	  * 		<Address>34 thingy street, someplace, sometown</Address>\n +
	  * 		</Customer>\n
	  * 	),
	  * 	(
	  * 		<Address>12 thingy street, someplace, sometown</Address>\n +
	  * 		</Customer>\n +
	  * 		</Customers>
	  * 	)
	  * )
	  * assert(computedRecords == expectedRecords)
	  * }}}
	  *
	  * @param hdfsPath the path of the file to read (folder or file, '*' works
	  * as well).
	  * @param sparkContext the SparkContext
	  * @param delimiter the specific record delimiter which replaces "\n"
	  * @return the RDD of records
	  */
	def textFileWithDelimiter(
		hdfsPath: String, sparkContext: SparkContext, delimiter: String
	): RDD[String] = {

		val conf = new Configuration(sparkContext.hadoopConfiguration)
		conf.set("textinputformat.record.delimiter", delimiter)

		sparkContext.newAPIHadoopFile(
			hdfsPath, classOf[TextInputFormat],
			classOf[LongWritable], classOf[Text], conf
		).map {
			case (_, text) => text.toString
		}
	}

	/** Saves and repartitions a key/value RDD on files whose name is the key.
	  *
	  * Within the provided outputFolder, will be one file per key in your
	  * keyValueRDD. And within a file for a given key are only values for this
	  * key.
	  *
	  * You need to know the nbr of keys beforehand (in general you use this to
	  * split your dataset in subsets, or to output one file per client, so you
	  * know how many keys you have). So you need to put as keyNbr the exact nbr
	  * of keys you'll have.
	  *
	  * This is not scalable. This shouldn't be considered for any data flow
	  * with normal or big volumes.
	  *
	  * {{{
	  * SparkHelper.saveAsTextFileByKey(myKeyValueRddToStore, "/my/output/folder/path", 12)
	  * }}}
	  *
	  * @param keyValueRDD the key/value RDD
	  * @param outputFolder the foldder where will be storrred key files
	  * @param keyNbr the nbr of expected keys (which is the nbr of outputed
	  * files).
	  */
	def saveAsTextFileByKey(
		keyValueRDD: RDD[(String, String)], outputFolder: String, keyNbr: Int
	) = {

		HdfsHelper.deleteFolder(outputFolder)

		keyValueRDD.partitionBy(
			new HashPartitioner(keyNbr)
		).saveAsHadoopFile(
			outputFolder, classOf[String], classOf[String], classOf[KeyBasedOutput]
		)
	}

	/** Saves and repartitions a key/value RDD on files whose name is the key.
	  *
	  * Within the provided outputFolder, will be one file per key in your
	  * keyValueRDD. And within a file for a given key are only values for this
	  * key.
	  *
	  * You need to know the nbr of keys beforehand (in general you use this to
	  * split your dataset in subsets, or to output one file per client, so you
	  * know how many keys you have). So you need to put as keyNbr the exact nbr
	  * of keys you'll have.
	  *
	  * This is not scalable. This shouldn't be considered for any data flow
	  * with normal or big volumes.
	  *
	  * {{{
	  * SparkHelper.saveAsTextFileByKey(myKeyValueRddToStore, "/my/output/folder/path", 12, classOf[BZip2Codec])
	  * }}}
	  *
	  * @param keyValueRDD the key/value RDD
	  * @param outputFolder the foldder where will be storrred key files
	  * @param keyNbr the nbr of expected keys (which is the nbr of outputed
	  * files).
	  * @param compressionCodec the type of compression to use (for instance
	  * classOf[BZip2Codec] or classOf[GzipCodec]))
	  */
	def saveAsTextFileByKey(
		keyValueRDD: RDD[(String, String)], outputFolder: String, keyNbr: Int,
		compressionCodec: Class[_ <: CompressionCodec]
	) = {

		HdfsHelper.deleteFolder(outputFolder)

		keyValueRDD.partitionBy(
			new HashPartitioner(keyNbr)
		).saveAsHadoopFile(
			outputFolder, classOf[String], classOf[String],
			classOf[KeyBasedOutput], compressionCodec
		)
	}

	/** Decreases the nbr of partitions of a folder.
	  *
	  * This is often handy when the last step of your job needs to run on
	  * thousands of files, but you want to store your final output on let's say
	  * only 300 files.
	  *
	  * It's like a FileUtil.copyMerge, but the merging produces more than one
	  * file.
	  *
	  * Be aware that this methods deletes the provided input folder.
	  *
	  * {{{
	  * SparkHelper.decreaseCoalescence(
	  * 	"/folder/path/with/2000/files", "/produced/folder/path/with/only/300/files", 300, sparkContext
	  * )
	  * }}}
	  *
	  * @param highCoalescenceLevelFolder the folder which contains 10000 files
	  * @param lowerCoalescenceLevelFolder the folder which will contain the
	  * same data as highCoalescenceLevelFolder but spread on only 300 files (
	  * where 300 is the finalCoalescenceLevel parameter).
	  * @param finalCoalescenceLevel the nbr of files within the folder at the
	  * end of this method.
	  * @param sparkContext the SparkContext
	  */
	def decreaseCoalescence(
		highCoalescenceLevelFolder: String, lowerCoalescenceLevelFolder: String,
		finalCoalescenceLevel: Int, sparkContext: SparkContext
	) = {
		decreaseCoalescenceInternal(
			highCoalescenceLevelFolder, lowerCoalescenceLevelFolder,
			finalCoalescenceLevel, sparkContext, None
		)
	}

	/** Decreases the nbr of partitions of a folder.
	  *
	  * This is often handy when the last step of your job needs to run on
	  * thousands of files, but you want to store your final output on let's say
	  * only 300 files.
	  *
	  * It's like a FileUtil.copyMerge, but the merging produces more than one
	  * file.
	  *
	  * Be aware that this methods deletes the provided input folder.
	  *
	  * {{{
	  * SparkHelper.decreaseCoalescence(
	  * 	"/folder/path/with/2000/files", "/produced/folder/path/with/only/300/files", 300, sparkContext, classOf[BZip2Codec]
	  * )
	  * }}}
	  *
	  * @param highCoalescenceLevelFolder the folder which contains 10000 files
	  * @param lowerCoalescenceLevelFolder the folder which will contain the
	  * same data as highCoalescenceLevelFolder but spread on only 300 files (
	  * where 300 is the finalCoalescenceLevel parameter).
	  * @param finalCoalescenceLevel the nbr of files within the folder at the
	  * end of this method.
	  * @param sparkContext the SparkContext
	  * @param compressionCodec the type of compression to use (for instance
	  * classOf[BZip2Codec] or classOf[GzipCodec]))
	  */
	def decreaseCoalescence(
		highCoalescenceLevelFolder: String, lowerCoalescenceLevelFolder: String,
		finalCoalescenceLevel: Int, sparkContext: SparkContext,
		compressionCodec: Class[_ <: CompressionCodec]
	) = {
		decreaseCoalescenceInternal(
			highCoalescenceLevelFolder, lowerCoalescenceLevelFolder,
			finalCoalescenceLevel, sparkContext, Some(compressionCodec)
		)
	}

	/** Saves as text file, but by decreasing the nbr of partitions of the output.
	  *
	  * Same as decreaseCoalescence, but the storage of the RDD in an
	  * intermediate folder is included.
	  *
	  * This still makes the processing parallelized, but the output is
	  * coalesced.
	  *
	  * {{{
	  * SparkHelper.saveAsTextFileAndCoalesce(myRddToStore, "/produced/folder/path/with/only/300/files", 300)
	  * }}}
	  *
	  * @param outputRDD the RDD to store, processed for instance on 10000 tasks
	  * (which would thus be stored as 10000 files).
	  * @param outputFolder the folder where will finally be stored the RDD but
	  * spread on only 300 files (where 300 is the value of the
	  * finalCoalescenceLevel parameter).
	  * @param finalCoalescenceLevel the nbr of files within the folder at the
	  * end of this method.
	  */
	def saveAsTextFileAndCoalesce(
		outputRDD: RDD[String], outputFolder: String, finalCoalescenceLevel: Int
	) = {

		val sparkContext = outputRDD.context

		// We remove folders where to store data in case they already exist:
		HdfsHelper.deleteFolder(outputFolder + "_tmp")
		HdfsHelper.deleteFolder(outputFolder)

		// We first save the rdd with the level of coalescence used during the
		// processing. This way the processing is done with the right level of
		// tasks:
		outputRDD.saveAsTextFile(outputFolder + "_tmp")

		// Then we read back this tmp folder, apply the coalesce and store it
		// back:
		decreaseCoalescenceInternal(
			outputFolder + "_tmp", outputFolder,
			finalCoalescenceLevel, sparkContext, None
		)
	}

	/** Saves as text file, but by decreasing the nbr of partitions of the output.
	  *
	  * Same as decreaseCoalescence, but the storage of the RDD in an
	  * intermediate folder is included.
	  *
	  * This still makes the processing parallelized, but the output is
	  * coalesced.
	  *
	  * {{{
	  * SparkHelper.saveAsTextFileAndCoalesce(myRddToStore, "/produced/folder/path/with/only/300/files", 300, classOf[BZip2Codec])
	  * }}}
	  *
	  * @param outputRDD the RDD to store, processed for instance on 10000 tasks
	  * (which would thus be stored as 10000 files).
	  * @param outputFolder the folder where will finally be stored the RDD but
	  * spread on only 300 files (where 300 is the value of the
	  * finalCoalescenceLevel parameter).
	  * @param finalCoalescenceLevel the nbr of files within the folder at the
	  * end of this method.
	  * @param compressionCodec the type of compression to use (for instance
	  * classOf[BZip2Codec] or classOf[GzipCodec]))
	  */
	def saveAsTextFileAndCoalesce(
		outputRDD: RDD[String], outputFolder: String, finalCoalescenceLevel: Int,
		compressionCodec: Class[_ <: CompressionCodec]
	) = {

		val sparkContext = outputRDD.context

		// We remove folders where to store data in case they already exist:
		HdfsHelper.deleteFolder(outputFolder + "_tmp")
		HdfsHelper.deleteFolder(outputFolder)

		// We first save the rdd with the level of coalescence used during the
		// processing. This way the processing is done with the right level of
		// tasks:
		outputRDD.saveAsTextFile(outputFolder + "_tmp")

		// Then we read back this tmp folder, apply the coalesce and store it
		// back:
		decreaseCoalescenceInternal(
			outputFolder + "_tmp", outputFolder,
			finalCoalescenceLevel, sparkContext, Some(compressionCodec)
		)
	}

	//////
	// Internal core:
	//////

	private def saveAsSingleTextFileWithWorkingFolderInternal(
		outputRDD: RDD[String], outputFile: String, workingFolder: String,
		compressionCodec: Option[Class[_ <: CompressionCodec]]
	) {

		// We chose a random name for the temporary file:
		val temporaryName = Random.alphanumeric.take(10).mkString("")

		// We perform the merge into a temporary single text file:
		saveAsSingleTextFileInternal(
			outputRDD, workingFolder + "/" + temporaryName, compressionCodec
		)

		// And then only we put the resulting file in its final real location:
		HdfsHelper.moveFile(
			workingFolder + "/" + temporaryName, outputFile,
			overwrite = true
		)
	}

	/** Saves RDD in exactly one file.
	  *
	  * Allows one to save an RDD as one text file, but at the same time to keep
	  * the processing parallelized.
	  *
	  * @param outputRDD the RDD of strings to save as text file
	  * @param outputFile the path where to save the file
	  * @param compression the compression codec to use (can be left to None)
	  */
	private def saveAsSingleTextFileInternal(
		outputRDD: RDD[String], outputFile: String,
		compressionCodec: Option[Class[_ <: CompressionCodec]]
	): Unit = {

		val fileSystem = FileSystem.get(new Configuration())

		// Classic saveAsTextFile in a temporary folder:
		HdfsHelper.deleteFolder(outputFile + ".tmp")
		if (compressionCodec.isEmpty)
			outputRDD.saveAsTextFile(outputFile + ".tmp")
		else
			outputRDD.saveAsTextFile(outputFile + ".tmp", compressionCodec.get)

		// Merge the folder into a single file:
		HdfsHelper.deleteFile(outputFile)
		FileUtil.copyMerge(
			fileSystem, new Path(outputFile + ".tmp"),
			fileSystem, new Path(outputFile),
			true, new Configuration(), null
		)
		HdfsHelper.deleteFolder(outputFile + ".tmp")
	}

	private def decreaseCoalescenceInternal(
		highCoalescenceLevelFolder: String, lowerCoalescenceLevelFolder: String,
		finalCoalescenceLevel: Int, sparkContext: SparkContext,
		compressionCodec: Option[Class[_ <: CompressionCodec]]
	): Unit = {

		val intermediateRDD = sparkContext.textFile(
			highCoalescenceLevelFolder
		).coalesce(
			finalCoalescenceLevel
		)

		if (compressionCodec.isEmpty)
			intermediateRDD.saveAsTextFile(lowerCoalescenceLevelFolder)
		else
			intermediateRDD.saveAsTextFile(
				lowerCoalescenceLevelFolder, compressionCodec.get
			)

		HdfsHelper.deleteFolder(highCoalescenceLevelFolder)
	}
}

private class KeyBasedOutput extends MultipleTextOutputFormat[Any, Any] {

	override def generateActualKey(key: Any, value: Any): Any = NullWritable.get()

	override def generateFileNameForKeyValue(
		key: Any, value: Any, name: String
	): String = {
		key.asInstanceOf[String]
	}
}
