package com.planet_ink.emutil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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

	private final List<String>	pages	= new ArrayList<String>();
	private final String		fileName;
	private final boolean		prependLineNumbers;

	/**
	 * Read a GeoWrite .CVT file from the given path.
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
	 * @param file the .CVT file
	 * @param prependLineNumbers add line numbers
	 * @throws IOException on read errors or if the file is not a GeoWrite document
	 */
	public GeoRWriter(final File file, final boolean prependLineNumbers) throws IOException
	{
		this.prependLineNumbers = prependLineNumbers;
		this.fileName = file.getName();
		try(final InputStream in = new FileInputStream(file))
		{
			parse(in);
		}
	}

	/**
	 * Read a GeoWrite .CVT file from the given stream.
	 * @param in the stream holding the .CVT data
	 * @param prependLineNumbers add line numbers
	 * @throws IOException on read errors or if the data is not a GeoWrite document
	 */
	public GeoRWriter(final InputStream in, final boolean prependLineNumbers) throws IOException
	{
		this.fileName = "<stream>";
		this.prependLineNumbers=prependLineNumbers;
		parse(in);
	}

	/**
	 * Read the whole stream into a byte array.
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
	 */
	private static boolean isCvt(final byte[] data)
	{
		if(data.length < 58)
			return false;
		return new String(data, 30, CVT_SIGNATURE1.length()).equals(CVT_SIGNATURE1)
				||new String(data, 30, CVT_SIGNATURE2.length()).equals(CVT_SIGNATURE2);
	}

	/**
	 * Decode the text of a single page branch.
	 * Skips the 27 byte ruler escape and 4 byte NEWCARDSET, then reads the
	 * text until an EOP ($0C), handling inline ruler/NEWCARDSET/graphics
	 * escapes and converting PETSCII to ASCII along the way.
	 */
	private String decodePage(final byte[] branch)
	{
		final StringBuilder str = new StringBuilder(branch.length);
		// find the NEWCARDSET code following the initial ruler escape
		int i = 1;
		while((i < 35) && (i < branch.length))
		{
			if((branch[i] & 0xff) == 0x17) // NEWCARDSET
			{
				i += 4;
				break;
			}
			i++;
		}
		int l = 1;
		if(prependLineNumbers)
			str.append((l<10?(" "+l):(""+l))+": ");
			
		final int n = branch.length;
		while(i < n)
		{
			final int b = branch[i] & 0xff;
			if(b == 0x0C) // EOP
				break;
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
			}
			else
			if(b == 0x09) // tab
				str.append('\t');
			else
			if((b >= 0x20) && (b <= 0x7E)) // plain ASCII
				str.append((char)b);
			else
			if(b == 0xA0) // PETSCII space
				str.append(' ');
			else
			if((b >= 0xC1) && (b <= 0xDA)) // PETSCII upper case letters
				str.append((char)(b - 0xC1 + 'A'));
			i++;
		}
		return str.toString();
	}

	/**
	 * Parse the file data and extract all pages.
	 */
	private void parse(final InputStream in) throws IOException
	{
		final byte[] data = readAll(in);
		if(data.length < DATA_OFFSET)
			throw new IOException("Not a valid GeoWrite CVT file (too short): "+fileName);
		if(!isCvt(data))
			throw new IOException("Not a GeoWrite CVT file (bad signature): "+fileName);
		final byte[] vlirSector = Arrays.copyOfRange(data, VLIR_SECTOR_OFF, VLIR_SECTOR_OFF + BLOCK_SIZE);
		int offset = DATA_OFFSET;
		for(int branch = 0; branch < MAX_PAGES; branch++)
		{
			final int numBlocks = vlirSector[branch * 2] & 0xff;
			final int extra = vlirSector[(branch * 2) + 1] & 0xff;
			if((numBlocks == 0) && (extra == 0xFF)) // null (unused) branch
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
			pages.add(decodePage(branchData));
		}
	}

	/**
	 * The number of pages in the document.
	 * @return the page count
	 */
	public int getNumPages()
	{
		return pages.size();
	}

	/**
	 * Get the decoded text of a single page.
	 * @param pageNum zero-based page index
	 * @return the page text
	 */
	public String getPage(final int pageNum)
	{
		return pages.get(pageNum);
	}

	/**
	 * Get the decoded text of all pages.
	 * @return a list of page texts, one per page
	 */
	public List<String> getPages()
	{
		return new ArrayList<String>(pages);
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
		System.out.println("  GeoRWriter REWRITE [file.cvt] [page] [line] [text]");
		System.out.println("  GeoRWriter DELETE [file.cvt] [page] [line]-[line]");
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
				//TODO: delete one or more lines of text
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
