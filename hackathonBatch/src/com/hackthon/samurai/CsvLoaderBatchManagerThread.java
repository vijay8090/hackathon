package com.hackthon.samurai;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Scanner;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.bson.types.ObjectId;

import com.hackthon.samurai.constants.CsvLoaderConstants;
import com.hackthon.samurai.util.CSVFileUtil;
import com.hackthon.samurai.util.MongoDBUtil;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;

/**
 * 
 * @author Vijayakumar Anbu
 * @project : Hackathon
 * @Date :Sep 19, 2015
 * @java Version : Java 8
 */
public class CsvLoaderBatchManagerThread implements Runnable {

	private static final String MONGODB_ID = "_id";

	public static final String CSV = CsvLoaderConstants.FILE_EXT;

	static Logger logger = LogManager.getLogger(CsvLoaderBatchManagerThread.class.getName());

	private Thread csvLoaderManagerThread;
	private boolean stop = false;
	private boolean interrupt = false;
	private boolean started = false;

	/**
	 * 
	 * Constructor {tags}
	 */
	public CsvLoaderBatchManagerThread() {
		start();
	}

	public void start() {
		stop = false;
		csvLoaderManagerThread = new Thread(this);
		csvLoaderManagerThread.setName("csvLoaderManagerThread");
		csvLoaderManagerThread.start();
	}

	/**
	 * 
	 * This Method is to stop
	 */
	public void stop() {
		stop = true;
		csvLoaderManagerThread = null;
	}

	/*
	 * This Method is to checkStopJobMonitor
	 * 
	 * @return
	 */
	private boolean checkStopJobMonitor() {
		logger.debug("start checkStopJobMonitor");
		boolean result = false;
		/*
		 * Check the local SCAN directory for a (zero-byte) “STOP_PROCESS” file
		 * If found, exit. This is a simple way to shut down the daemon
		 */
		if (new File(CsvLoaderConstants.FILE_STOP_CSV_LOADER).exists()) {
			result = true;
		}

		logger.debug("end checkStopJobMonitor");
		return result;
	}

	private boolean startCsvParser() {
		logger.debug("start startCsvParser");
		boolean result = false;
		started = true;
		// Check for stop monitor,
		/*
		 * Check the local Scan directory for a (zero-byte) “STOP_PROCESS” file
		 * If found, exit. This is a simple way to shut down the daemon
		 */

		// Scan the landing zone/pending directory for csv files
		File[] csvFiles = getCsvFiles(CsvLoaderConstants.DIR_SCAN);
		// List the unlocked files in datetime order, oldest first
		if (csvFiles != null && csvFiles.length > 0) {
			logger.debug("Total file count " + csvFiles.length);
			csvFiles = sortFilesOldestFirst(csvFiles);

			for (File csvFile : csvFiles) {

				try {
					CSVFileUtil.moveFile(csvFile, CsvLoaderConstants.DIR_SUBMIT_WORKING);

					boolean processResult = processCsvFile(
							new File(CsvLoaderConstants.DIR_SUBMIT_WORKING + File.separator + csvFile.getName()));

					if (!processResult) {
						CSVFileUtil.moveFile(csvFile, CsvLoaderConstants.DIR_SUBMIT_FAILED);

						logger.info("CSV Data Loader failed");
					}

				} catch (Exception e) {
					logger.error(e);
					e.printStackTrace();
					logger.error("Exception in " + csvFile.getName() + "file " + e.getMessage());
					try {
						FileUtils.moveFileToDirectory(csvFile, new File(CsvLoaderConstants.DIR_SUBMIT_FAILED), false);
						logger.info("CSV Data Loader failed  <br/> Details : " + e.getMessage()
								+ CsvLoaderConstants.DIR_SUBMIT_FAILED);

					} catch (Exception e1) {
						e1.printStackTrace();
					}
				}
			}
		} else {
			logger.debug("No files to process");
		}
		started = false;
		logger.debug("end startCsvParser");
		return result;
	}

	/**
	 * 
	 * This Method is to getUnlockedFiles
	 * 
	 * @param scanDir
	 * @return
	 */
	private File[] getCsvFiles(String dir) {
		logger.debug("start getCsvFiles");
		// Scan the landing zone directory for csv files

		File[] files = new File(dir).listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(CSV);
			}
		});

		logger.debug("end getCsvFiles");
		return files;
	}

	/**
	 * 
	 * This Method is to sortFilesLatestFirst
	 * 
	 * @param files
	 * @return
	 */
	private File[] sortFilesOldestFirst(File[] files) {
		logger.debug("start sortFilesLatestFirst");
		// Sort the files based on the modified date, oldest files will be
		// displayed first.
		logger.debug("Before Sorting :");
		for (File f : files) {
			logger.debug("Name : " + f.getName() + " modified date : " + new Date(f.lastModified()).toString());
		}
		Arrays.sort(files, new Comparator<File>() {
			public int compare(File f1, File f2) {
				return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
			}
		});
		logger.debug("After Sorting :");
		for (File f : files) {
			logger.debug("Name : " + f.getName() + " modified date : " + new Date(f.lastModified()).toString());
		}
		logger.debug("end sortFilesLatestFirst");
		return files;
	}

	/**
	 * 
	 * This Method is to doJobMonitor
	 * 
	 * @param jobFile
	 * @param ilbean
	 * @return
	 */
	private boolean processCsvFile(File csvFile) throws Exception {
		logger.debug("start processCsvFile");
		boolean result = true;

		Scanner scanner = null;
		String line = "";
		String cvsSplitBy = ",";

		MongoClient mongoClient = null;

		try {

			mongoClient = MongoDBUtil.getConnection();

			DB db = mongoClient.getDB(MongoDBUtil.MONGODB_DEFAULT_DB);

			DBCollection table = db.getCollection("ITEM_ATTR");

			// Read the data from csv file
			scanner = new Scanner(csvFile);
			while (scanner.hasNextLine()) {
				line = scanner.nextLine();
				String[] contents = line.split(cvsSplitBy);
				int totalCount = contents.length;
				if (totalCount == 3) {

					String id = contents[0].trim();
					String key = contents[2].trim();
					String value = contents[3].trim();

					DBObject document = findDocumentById(table, id);

					if (document != null) {

						document.put(key, value);

						BasicDBObject searchQuery = new BasicDBObject();
						searchQuery.put(MONGODB_ID, document.get(MONGODB_ID));

						BasicDBObject updateObj = new BasicDBObject();
						updateObj.put("$set", document);

						table.update(searchQuery, updateObj);

					} else {
						document = new BasicDBObject();
						document.put(MONGODB_ID, id);
						document.put(key, value);
						table.insert(document);
					}

				} else {
					logger.info("CSV input error" + line);
				}

			}

			scanner.close();
			MongoDBUtil.closeConnection(mongoClient);

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {

		}

		CSVFileUtil.moveFile(csvFile, CsvLoaderConstants.DIR_SUBMIT_SUCCESS);
		logger.debug("end processCsvFile");
		return result;
	}

	/**
	 * 
	 * This Method is to find mongobd Document ById
	 * 
	 * @param table
	 * @param id
	 * @return
	 */
	public DBObject findDocumentById(DBCollection table, String id) {

		BasicDBObject document = new BasicDBObject();
		document.put(MONGODB_ID, id);

		BasicDBObject searchQuery = new BasicDBObject();
		searchQuery.put(MONGODB_ID, document.get(MONGODB_ID));

		// DBObject query = new BasicDBObject("_id", new ObjectId(id));
		// query.put();
		DBObject dbObj = table.findOne(searchQuery);
		return dbObj;
	}

	public void run() {
		while (csvLoaderManagerThread != null) {
			try {
				if (!started) {
					if (!checkStopJobMonitor()) {
						// Start the job Monitor Process
						startCsvParser();
					} else {
						logger.debug("Stopping!!  “STOPMONITOR” file exists");
					}
				}
				if (interrupt) {
					logger.debug("Stopping!!");
					stop();
				}
			} catch (Exception e) {
				logger.debug("exception in thread ");
				e.printStackTrace();
			}

			try {
				logger.debug("Pausing the executing for " + CsvLoaderConstants.CSV_PARSER_MONITOR_SLEEP + " Secs ");
				Thread.sleep(CsvLoaderConstants.CSV_PARSER_MONITOR_SLEEP * 1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
