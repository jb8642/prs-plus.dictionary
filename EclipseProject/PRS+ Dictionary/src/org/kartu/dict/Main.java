package org.kartu.dict;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.kartu.IOUtils;
import org.kartu.dict.stardict.StardictParser;
import org.kartu.dict.xdxf.visual.XDXFParser;

import ds.tree.DuplicateKeyException;
import ds.tree.RadixTreeImpl;

/**
 * PRS+ dictionary file format is:
 *  
 *  (all offsets are absolute)
 * 
 *  [header]
 *  [articles]
 *  [word list]
 *  [radix]
 *  
 *  header : 
 *  	"PRSPDICT" (ascii)
 *  	version lo (uint8) 
 *  	version hi (uint8)
 *  	radix offset (uint32)
 *  	... rest is padded with zeros up to 1024 bytes
 *  
 *  articles : article*
 *   
 *  article:
 *  	length (unit32)
 *  	article (utf8 TEXT)
 *  
 *  word list:
 *   	name (UTF8)
 *   	\0
 *   	short translation (up to SHORT_TRANSLATION_LEN chars, UTF8)
 *   	\0
 *  
 *  radix: node*
 *  
 *  node: 
 *  	length - size of the structure in bytes (uint16)
 *  	article offset - (uint32)
 *  	word list offset - (uint32)
 *  	number of child nodes - (uint8)
 *  	offsets of child nodes (uint32 * number of child nodes)
 *  	zero terminated UTF16 names of child nodes (length can be determined by total length of the node)
 *  
 * @author kartu
 */
public class Main {
	private static final String EXT_PRSPDICT = ".prspdict";
	private static final Logger log = Logger.getLogger(Main.class);
	
	static final int HEADER_SIZE = 1024;
	static final Charset KEY_CHARSET = Charset.forName("UTF-16LE");
	static final Charset ARTICLE_CHARSET = Charset.forName("UTF-8");
	private static final int SHORT_TRANSLATION_LEN = 80;
	
	public static void main(String[] args) throws IOException, DictionaryParserException {
		if (args.length < 1 || args.length > 2) {
			printUsage();
			System.exit(0);
		}
		
		String inputFileName = args[0];
		String outputFileName;
		if (args.length >= 2) {
			outputFileName = args[1];
		} else {
			outputFileName = args[0];
			int idx = outputFileName.indexOf('.');
			if (idx > -1) {
				outputFileName = outputFileName.substring(0, idx);
			}
		}
		
		if (!outputFileName.endsWith(EXT_PRSPDICT)) {
			outputFileName += EXT_PRSPDICT;
		}

		// Write header (zeros)
		RandomAccessFile outputFile = new RandomAccessFile(outputFileName, "rw");
		outputFile.setLength(HEADER_SIZE);
		outputFile.seek(HEADER_SIZE);
		
		// Temporary file for quick lookup of words with closest match
		RandomAccessFile wordListFile = new RandomAccessFile(File.createTempFile("prspDictTemp", EXT_PRSPDICT), "rw");

		// Open input dictionary file
		IDictionaryParser parser;
		if (inputFileName.endsWith(XDXFParser.EXTENSION)) {
			parser = new XDXFParser();
		} else if (inputFileName.endsWith(StardictParser.EXTENSION)) {
			parser = new StardictParser();
		} else {
			System.out.println(String.format("Unknown extension, please provide file with either %s (xdxf) or %s (stardict) extension", XDXFParser.EXTENSION, StardictParser.EXTENSION));
			System.exit(0);
			return;
		}
		parser.open(inputFileName);

		log.info("Reading articles (might take a while)...");
		
		// To solve the problem with duplicate articles, need to read everything into memory first
		HashMap<String, IDictionaryArticle> articles = new HashMap<String, IDictionaryArticle>(10000);
		// anonymous block, reading all articles, combining those with clashing keywords
		{
			IDictionaryArticle article;
			while ((article = parser.getNext()) != null) {
				String keyword = article.getKeyword();
				IDictionaryArticle existingArticle = articles.get(keyword);
				
				if (existingArticle != null) {
					// Append definition
					existingArticle.append(article);
				} else {
					articles.put(keyword, article);
				}
			}
			
			parser.close();
		}
		
		
		RadixTreeImpl<int[]> tree = new RadixTreeImpl<int[]>();
		int articlesLen = 0;
		int wordListLen = 0;
		long nArticles = 0;
		
		for (IDictionaryArticle article : articles.values()) {
			String keyword = article.getKeyword();
			if (keyword == null) {
				// ignore articles without keyword
				continue;
			}
			// translation
			String translation = article.getTranslation();
			String shortTranslation = article.getShortTranslation();
			byte[] content = translation.getBytes(ARTICLE_CHARSET);
			// keyword + short translation
			int shortTranslationLen = Math.min(SHORT_TRANSLATION_LEN, shortTranslation.length());
			// aka word list record
			shortTranslation = shortTranslation.substring(0, shortTranslationLen);
			shortTranslation = shortTranslation.replaceAll("\\-", " " );
			shortTranslation = shortTranslation.replaceAll("[\\s]+", " ");
			shortTranslation = keyword + '\0' + shortTranslation+ '\0';
			
			 
			byte[] shortContent = shortTranslation.getBytes(ARTICLE_CHARSET);
			try {
				tree.insert(keyword, new int[] {articlesLen, wordListLen});
				IOUtils.writeInt(outputFile, content.length);
				outputFile.write(content);
				wordListFile.write(shortContent);
				// 4 bytes is length of the article
				articlesLen += content.length + 4; 
				wordListLen += shortContent.length;
				nArticles++;
			} catch (DuplicateKeyException e) {
				log.warn("Duplicate article: " + keyword + " this should never happen, converter is bugged!");
			}
		}
		parser.close();
		log.info("Finished reading articles (" + nArticles + ")");

		//------------------------------- Write header --------------------------------------
		log.info("Writing header");
		outputFile.seek(0);
		// magic
		outputFile.write("PRSPDICT".getBytes("ASCII"));
		// header size
		IOUtils.writeShort(outputFile, HEADER_SIZE - 8 /* magic */);
		
		// version
		outputFile.write(0); // lo
		outputFile.write(1); // hi
		
		// index offset
		int radixOffset = articlesLen + wordListLen + HEADER_SIZE;
		int wordListOffset = articlesLen + HEADER_SIZE;
		IOUtils.writeInt(outputFile, wordListOffset);
		IOUtils.writeInt(outputFile, radixOffset);
		
		// rewind past header + articles
		outputFile.seek(articlesLen + HEADER_SIZE);
		
		// write word list
		wordListFile.seek(0); 
		IOUtils.writeRaf(outputFile, wordListFile);
		
		// File position must equal radix offset
		assert(outputFile.getFilePointer() == radixOffset);
		
		// Write index
		log.info("Writing indices (should take even longer than reading articles)");
		RadixSerializer.getInstance().persistRadix(KEY_CHARSET, radixOffset, HEADER_SIZE, HEADER_SIZE + articlesLen, tree.root, outputFile);
		outputFile.close();
		
		log.info("OK");
	}

	private static void printUsage() throws IOException {
		Properties props = new Properties();
		props.load(Main.class.getResourceAsStream("/main.properties"));
		System.out.println("XDXF to prspdict converter" 
				+ "\nVersion " + props.getProperty("version", "?.?") 
				+ "\nUsage:\n\t java -jar <jar file> <input xdxf file> [<output file>]"
				+ "\nUsage:\n\t java -jar <jar file> <input stardict (ifo) file> [<output file>]");
	}
}