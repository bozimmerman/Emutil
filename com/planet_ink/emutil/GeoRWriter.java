package com.planet_ink.emutil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
Copyright 2026-2026 Bo Zimmerman

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
/**
 * Reader/Writer for GEOS GeoWrite v2.0/v2.1 .CVT document files.
 *
 * A GeoWrite file is a VLIR structured GEOS file.  The first 61 branches
 * (0-60) hold the (up to 61) pages, branch 61 is the header record, branch
 * 62 the footer, branch 63 reserved, and branches 64-127 hold photo scraps.
 *
 * When stored loose (a .CVT file), the layout is:
 *   block 0 (254 bytes)   synthetic GEOS header w/ "PRG formatted GEOS file V1.0"
 *   block 1 (254 bytes)   GEOS file header from the directory entry
 *   block 2 (254 bytes)   VLIR sector - per branch a [numBlocks, extra] pair
 *   page data             one branch's data after another, each padded out
 *                         to whole 254-byte blocks (last branch truncated)
 *
 * Each page begins with a 27 byte ruler escape ($11 ...), a 4 byte
 * NEWCARDSET ($17, font id, style), and then the text.  The text may also
 * contain inline ruler escapes ($11), NEWCARDSET changes ($17), and
 * graphics escapes ($10).  A page is terminated by an EOP ($0C).
 *
 * @author BZ
 */
public class GeoRWriter
{
	private static final int	BLOCK_SIZE		= 254;
	private static final int	VLIR_SECTOR_OFF	= BLOCK_SIZE * 2; // 508
	private static final int	DATA_OFFSET		= BLOCK_SIZE * 3; // 762
	private static final int	MAX_PAGES		= 61;

	private static final String CVT_SIGNATURE1 = "PRG formatted GEOS file V1.0";
	private static final String CVT_SIGNATURE2 = "SEQ formatted GEOS file V1.0";

	private static final int VLIR_NULL_EXTRA	= 0xFF; // with 0 blocks: unused branch

	private final List<RawPage>	rawPages	= new ArrayList<RawPage>();
	private final String		fileName;
	private final boolean		prependLineNumbers;
	private final File		sourceFile;
	private byte[]		rawFile	= new byte[0];
	private byte[]		vlirSector	= new byte[0];
	private int		tailOffset	= DATA_OFFSET;

	/**
	 * A single page branch from the VLIR: its raw bytes exactly as stored,
	 * the decoded text of the page, and a map of where each text line
	 * begins inside the raw branch bytes.  All fields are produced by the
	 * single walk in parsePage().
	 */
	private static final class RawPage
	{
		final int		branch;
		final byte[]	raw;
		final int		textStart;
		final int		eopPos;
		final int		contentEnd;
		final int[]	lineStarts;
		final String	text;

		RawPage(final int branch, final byte[] raw, final int textStart, final int eopPos,
				final int contentEnd, final int[] lineStarts, final String text)
		{
			this.branch = branch;
			this.raw = raw;
			this.textStart = textStart;
			this.eopPos = eopPos;
			this.contentEnd = contentEnd;
			this.lineStarts = lineStarts;
			this.text = text;
		}

		int getNumLines()
		{
			return lineStarts.length - 1;
		}

		boolean isEmptyText()
		{
			return textStart >= eopPos;
		}
	}

	/**
	 * Read a GeoWrite .CVT file from the given path.
	 *
	 * @param filename the path to the .CVT file
	 * @param prependLineNumbers add line numbers
	 * @throws IOException on read errors or if the file is not a GeoWrite document
	 */
	public GeoRWriter(final String filename, final boolean prependLineNumbers) throws IOException
	{
		this(new File(filename), prependLineNumbers);
	}

	/**
	 * Read a GeoWrite .CVT file from the given file.
	 *
	 * @param file the .CVT file
	 * @param prependLineNumbers add line numbers
	 * @throws IOException on read errors or if the file is not a GeoWrite document
	 */
	public GeoRWriter(final File file, final boolean prependLineNumbers) throws IOException
	{
		this.prependLineNumbers = prependLineNumbers;
		this.fileName = file.getName();
		this.sourceFile = file;
		try(final InputStream in = new FileInputStream(file))
		{
			parse(in);
		}
	}

	/**
	 * Read a GeoWrite .CVT file from the given stream.
	 *
	 * @param in the stream holding the .CVT data
	 * @param prependLineNumbers add line numbers
	 * @throws IOException on read errors or if the data is not a GeoWrite document
	 */
	public GeoRWriter(final InputStream in, final boolean prependLineNumbers) throws IOException
	{
		this.fileName = "<stream>";
		this.prependLineNumbers=prependLineNumbers;
		this.sourceFile = null;
		parse(in);
	}

	/**
	 * Read the whole stream into a byte array.
	 *
	 * @param in the input stream
	 * @return byte[] the entire file bytes
	 */
	private static byte[] readAll(final InputStream in) throws IOException
	{
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int read;
		while((read = in.read(buf)) >= 0)
			bout.write(buf, 0, read);
		return bout.toByteArray();
	}

	/**
	 * Check whether the data looks like a loose GeoWrite .CVT file by looking
	 * for the GEOS file signature.
	 *
	 * @param data first part of the file
	 * @return true if cvt
	 */
	private static boolean isCvt(final byte[] data)
	{
		if(data.length < 58)
			return false;
		return new String(data, 30, CVT_SIGNATURE1.length()).equals(CVT_SIGNATURE1)
				||new String(data, 30, CVT_SIGNATURE2.length()).equals(CVT_SIGNATURE2);
	}

	/**
	 * Walk a single page branch once, producing both its decoded text and
	 * a map of where every line of that text begins and ends in the raw
	 * bytes, so that LIST line numbers and DELETE line ranges can never
	 * disagree.
	 * 	 
	 * @param branch 
	 * @param raw 
	 * @param declaredLen
	 * @return RawPage the decoded page
	 */
	private RawPage parsePage(final int branch, final byte[] raw, final int declaredLen)
	{
		final StringBuilder str = new StringBuilder(raw.length);
		// find the NEWCARDSET code following the initial ruler escape
		int i = 1;
		while((i < 35) && (i < raw.length))
		{
			if((raw[i] & 0xff) == 0x17) // NEWCARDSET
			{
				i += 4;
				break;
			}
			i++;
		}
		final int textStart = i;
		final List<Integer> starts = new ArrayList<Integer>();
		starts.add(Integer.valueOf(i));
		int l = 1;
		if(prependLineNumbers)
			str.append((l<10?(" "+l):(""+l))+": ");
		int eop = -1;
		while(i < raw.length)
		{
			final int b = raw[i] & 0xff;
			if(b == 0x0C) // EOP
			{
				eop = i;
				break;
			}
			else
			if(b == 0x11) // ruler escape
			{
				i += 27;
				continue;
			}
			else
			if(b == 0x17) // NEWCARDSET
			{
				i += 4;
				continue;
			}
			else
			if(b == 0x10) // graphics escape
			{
				i += 6;
				continue;
			}
			else
			if(b == 0x0D) // carriage return
			{
				str.append('\n');
				if(prependLineNumbers)
				{
					l++;
					str.append((l<10?(" "+l):(""+l))+": ");
				}
				i++;
				starts.add(Integer.valueOf(i));
			}
			else
			if(b == 0x09) // tab
			{
				str.append('\t');
				i++;
			}
			else
			if((b >= 0x20) && (b <= 0x7E)) // plain ASCII
			{
				str.append((char)b);
				i++;
			}
			else
			if(b == 0xA0) // PETSCII space
			{
				str.append(' ');
				i++;
			}
			else
			if((b >= 0xC1) && (b <= 0xDA)) // PETSCII upper case letters
			{
				str.append((char)(b - 0xC1 + 'A'));
				i++;
			}
			else
				i++;
		}
		final int eopPos = (eop < 0) ? raw.length : eop;
		int ce = Math.min(Math.max(declaredLen, 0), raw.length);
		if((eop >= 0) && (eop + 1 > ce))
			ce = eop + 1;
		starts.add(Integer.valueOf(eopPos));
		final int[] lineStarts = new int[starts.size()];
		for(int x = 0; x < starts.size(); x++)
			lineStarts[x] = starts.get(x).intValue();
		return new RawPage(branch, raw, textStart, eopPos, ce, lineStarts, str.toString());
	}

	/**
	 * Parse the file data, extracting all pages while preserving the raw
	 * file bytes, the VLIR sector, every page branch as stored, and the
	 * offset where the non-page branch data (header/footer/photo scraps)
	 * begins, so that the document can later be reconstructed.
	 */
	private void parse(final InputStream in) throws IOException
	{
		parse(readAll(in));
	}

	/**
	 * Parse the given raw .CVT file data, resetting all internal state.
	 */
	private void parse(final byte[] data) throws IOException
	{
		if(data.length < DATA_OFFSET)
			throw new IOException("Not a valid GeoWrite CVT file (too short): "+fileName);
		if(!isCvt(data))
			throw new IOException("Not a GeoWrite CVT file (bad signature): "+fileName);
		this.rawFile = data;
		this.vlirSector = Arrays.copyOfRange(data, VLIR_SECTOR_OFF, VLIR_SECTOR_OFF + BLOCK_SIZE);
		this.rawPages.clear();
		int offset = DATA_OFFSET;
		for(int branch = 0; branch < MAX_PAGES; branch++)
		{
			final int numBlocks = vlirSector[branch * 2] & 0xff;
			final int extra = vlirSector[(branch * 2) + 1] & 0xff;
			if((numBlocks == 0) && (extra == VLIR_NULL_EXTRA)) // null (unused) branch
				continue;
			if((numBlocks == 0) && (extra == 0)) // end of VLIR sector
				break;
			int branchLen = numBlocks * BLOCK_SIZE;
			if(offset + branchLen > data.length)
				branchLen = data.length - offset; // last branch may be truncated
			if(branchLen <= 0)
				break;
			final byte[] branchData = Arrays.copyOfRange(data, offset, offset + branchLen);
			offset += branchLen;
			// the VLIR entry declares where the branch content truly ends:
			// whole blocks when extra==1, otherwise extra-1 bytes past those
			final int declaredLen = (extra <= 1)
					? (numBlocks * BLOCK_SIZE)
					: (((numBlocks - 1) * BLOCK_SIZE) + extra - 1);
			rawPages.add(parsePage(branch, branchData, declaredLen));
		}
		this.tailOffset = offset;
	}

	/**
	 * The number of pages in the document.
	 * @return the page count
	 */
	public int getNumPages()
	{
		return rawPages.size();
	}

	/**
	 * Get the decoded text of a single page.
	 * @param pageNum zero-based page index
	 * @return the page text
	 */
	public String getPage(final int pageNum)
	{
		return rawPages.get(pageNum).text;
	}

	/**
	 * Get the decoded text of all pages.
	 * @return a list of page texts, one per page
	 */
	public List<String> getPages()
	{
		final List<String> texts = new ArrayList<String>(rawPages.size());
		for(final RawPage page : rawPages)
			texts.add(page.text);
		return texts;
	}

	/**
	 * The number of text lines on a single page, as numbered by LIST.
	 * @param pageNum 1-based page number
	 * @return the line count of that page
	 */
	public int getNumLines(final int pageNum)
	{
		if((pageNum < 1) || (pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		return rawPages.get(pageNum - 1).getNumLines();
	}

	/**
	 * Delete one or more lines of text from a page, then repair the VLIR
	 * sector and regenerate the source .CVT file, preserving everything
	 * else in the document exactly as it was stored.
	 *
	 * If the deletion renders the page empty of text, the page itself is
	 * deleted: its VLIR entry is marked null and its data removed.
	 *
	 * @param pageNum 1-based page number
	 * @param lineFrom 1-based first line to delete
	 * @param lineTo 1-based last line to delete (inclusive)
	 * @return true if the page was rendered empty and therefore removed
	 * @throws IOException on read/write errors, or if this was read from a stream
	 */
	public boolean deleteLines(final int pageNum, final int lineFrom, final int lineTo) throws IOException
	{
		if((pageNum < 1) || (pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		final RawPage page = rawPages.get(pageNum - 1);
		final int numLines = page.getNumLines();
		if((lineFrom < 1) || (lineTo < lineFrom) || (lineTo > numLines))
			throw new IndexOutOfBoundsException("Lines "+lineFrom+"-"+lineTo+" out of range (1-"+numLines+")");
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");

		final int start = page.lineStarts[lineFrom - 1];
		final int end = page.lineStarts[lineTo];
		final int delLen = end - start;
		final int newContentEnd;
		if(start >= page.contentEnd) // deleted only padding bytes
			newContentEnd = page.contentEnd;
		else
		if(end <= page.contentEnd) // deleted entirely within the content
			newContentEnd = page.contentEnd - delLen;
		else // deleted from the content into the padding
			newContentEnd = start;
		final byte[] newRaw = new byte[page.raw.length - delLen];
		System.arraycopy(page.raw, 0, newRaw, 0, start);
		System.arraycopy(page.raw, end, newRaw, start, page.raw.length - end);
		final RawPage newPage = parsePage(page.branch, newRaw, newContentEnd);
		final boolean removePage = newPage.isEmptyText() || (newContentEnd <= newPage.textStart);

		final byte[] newVlir = vlirSector.clone();
		final ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
		final boolean lastInFile = (pageNum == rawPages.size()) && (tailOffset >= rawFile.length);
		for(int i = 0; i < rawPages.size(); i++)
		{
			final RawPage branchPage = rawPages.get(i);
			if(i != pageNum - 1)
			{
				dataOut.write(branchPage.raw, 0, branchPage.raw.length);
				continue;
			}
			if(removePage)
			{
				newVlir[branchPage.branch * 2] = 0;
				newVlir[(branchPage.branch * 2) + 1] = (byte) VLIR_NULL_EXTRA;
				continue;
			}
			final int numBlocks = (newContentEnd + BLOCK_SIZE - 1) / BLOCK_SIZE;
			final int rem = newContentEnd % BLOCK_SIZE;
			final int extra = (rem == 0) ? 1 : (rem + 1);
			final byte[] stored;
			if(lastInFile)
				stored = Arrays.copyOf(newRaw, newContentEnd);
			else
				stored = Arrays.copyOf(newRaw, numBlocks * BLOCK_SIZE);
			newVlir[branchPage.branch * 2] = (byte) numBlocks;
			newVlir[(branchPage.branch * 2) + 1] = (byte) extra;
			dataOut.write(stored, 0, stored.length);
		}
		dataOut.write(rawFile, tailOffset, rawFile.length - tailOffset);

		final ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
		fileOut.write(rawFile, 0, VLIR_SECTOR_OFF);
		fileOut.write(newVlir, 0, newVlir.length);
		fileOut.write(dataOut.toByteArray(), 0, dataOut.size());
		final byte[] newFile = fileOut.toByteArray();

		final File temp = new File(sourceFile.getParentFile(), sourceFile.getName() + ".tmp");
		try(final FileOutputStream fout = new FileOutputStream(temp))
		{
			fout.write(newFile);
		}
		if((temp.length() != newFile.length) || (!sourceFile.delete()))
		{
			temp.delete();
			throw new IOException("Error replacing "+fileName);
		}
		if(!temp.renameTo(sourceFile))
			throw new IOException("Error renaming "+temp.getName()+" to "+fileName);
		parse(newFile);
		return removePage;
	}

	private static void usage()
	{
		System.out.println("GeoRWriter v1.0 - GeoWrite .CVT text extractor/inserter");
		System.out.println("");
		System.out.println("USAGE:");
		System.out.println("  GeoRWriter READ [file.cvt] [page]");
		System.out.println("    - Display all pages or one page of text");
		System.out.println("  GeoRWriter LIST [file.cvt] [page]");
		System.out.println("    - Display all pages or one page of text w/ line numbers");
		System.out.println("  GeoRWriter INSERT [file.cvt] [page] [line] [text]");
		System.out.println("    - Insert one or more lines of text, rewriting the file.");
		System.out.println("  GeoRWriter REWRITE [file.cvt] [page] [line] [text]");
		System.out.println("    - Replace one or more lines of text, rewriting the file.");
		System.out.println("  GeoRWriter DELETE [file.cvt] [page] [line]-[line]");
		System.out.println("    - Delete one or more lines of text, rewriting the file.");
		System.out.println("");
		System.out.println("  file.cvt  path to a GeoWrite .CVT document");
		System.out.println("  page      1-based page number (optional for read)");
		System.out.println("  line      1-based line number");
		return;

	}
	
	/**
	 * Command line tool to print GeoWrite .CVT file text to stdout.
	 * @param args one or two arguments: file path, and optional 1-based page number
	 */
	public static void main(final String[] args)
	{
		if(args.length < 2)
		{
			usage();
			return;
		}
		try
		{
			if(args[0].equalsIgnoreCase("READ")||args[0].equalsIgnoreCase("LIST"))
			{
				final boolean showLineNumbers = args[0].equalsIgnoreCase("LIST");
				final GeoRWriter reader = new GeoRWriter(args[1],showLineNumbers);
				if(args.length == 3)
				{
					final int pageNum;
					try
					{
						pageNum = Integer.parseInt(args[2]);
					}
					catch(final NumberFormatException e)
					{
						System.err.println("Error: invalid page number '"+args[2]+"'");
						return;
					}
					if((pageNum < 1) || (pageNum > reader.getNumPages()))
					{
						System.err.println("Error: page "+pageNum+" out of range (1-"+reader.getNumPages()+")");
						return;
					}
					System.out.println(reader.getPage(pageNum - 1));
				}
				else
				{
					final List<String> pages = reader.getPages();
					for(int i = 0; i < pages.size(); i++)
					{
						System.out.println("=== Page " + (i + 1) + " ===");
						System.out.println(pages.get(i));
					}
				}
			}
			else
			if(args[0].equalsIgnoreCase("INSERT"))
			{
				//TODO: insert a new line of plain text
			}
			else
			if(args[0].equalsIgnoreCase("REWRITE"))
			{
				//TODO: replace an existing line of plain text
			}
			else
			if(args[0].equalsIgnoreCase("DELETE"))
			{
				if(args.length < 4)
				{
					usage();
					return;
				}
				final int pageNum;
				try
				{
					pageNum = Integer.parseInt(args[2]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid page number '"+args[2]+"'");
					return;
				}
				final int dash = args[3].indexOf('-');
				final String fromStr = (dash < 0) ? args[3] : args[3].substring(0, dash);
				final String toStr = (dash < 0) ? args[3] : args[3].substring(dash + 1);
				final int lineFrom;
				final int lineTo;
				try
				{
					lineFrom = Integer.parseInt(fromStr.trim());
					lineTo = Integer.parseInt(toStr.trim());
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid line range '"+args[3]+"'");
					return;
				}
				final GeoRWriter writer = new GeoRWriter(args[1],false);
				if((pageNum < 1) || (pageNum > writer.getNumPages()))
				{
					System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
					return;
				}
				final int numLines = writer.getNumLines(pageNum);
				if((lineFrom < 1) || (lineTo < lineFrom) || (lineTo > numLines))
				{
					System.err.println("Error: lines "+lineFrom+"-"+lineTo+" out of range (1-"+numLines+")");
					return;
				}
				final boolean pageRemoved = writer.deleteLines(pageNum, lineFrom, lineTo);
				if(pageRemoved)
					System.out.println("Deleted lines "+lineFrom+"-"+lineTo+"; page "+pageNum+" is now empty and was removed.");
				else
					System.out.println("Deleted lines "+lineFrom+"-"+lineTo+" from page "+pageNum+".");
			}
			else
			{
				usage();
			}

		}
		catch(final Exception e)
		{
			System.err.println("Error: " + e.getMessage());
		}
	}
}
