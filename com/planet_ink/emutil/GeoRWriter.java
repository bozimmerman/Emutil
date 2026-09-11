package com.planet_ink.emutil;

import java.io.*;
import java.util.*;
import java.util.regex.*;

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
 * Delegates to GeoMod for binary VLIR file management, adding text-level
 * parsing and editing on top of raw record bytes.
 */
public class GeoRWriter
{
	private static final int	BLOCK_SIZE			= GeoMod.BLOCK_SIZE; // 254
	private static final int	VLIR_SECTOR_OFF		= GeoMod.VLIR_SECTOR_OFF; // 508
	public static final int		DATA_OFFSET			= GeoMod.DATA_OFFSET; // 762
	private static final int	MAX_PAGES			= 61;
	private static final int	MAX_LINES_PER_PAGE	= 68; // GeoWrite documents; a page is considered "full" past this many lines.
	public static final int		VLIR_NULL_EXTRA		= GeoMod.VLIR_NULL_EXTRA;
	private static final String CVT_GEOAUTHOR		= "geoWrite";		// app author/version string in block 1
	private static final String CVT_GEOIMAGE		= "Write Image";	// app name string in block 1


	// Delegate to GeoMod for binary VLIR file management (header, raw file, record parsing)
	protected GeoMod geoMod;
	
	private final List<GWPage> 	rawPages 	= new ArrayList<GWPage>();
	private final String 		fileName;
	private final boolean 		prependLineNumbers;
	private final File 			sourceFile;
	private byte[] 				rawFile 	= new byte[0];
	private byte[] 				vlirSector 	= new byte[0];
	private int 				tailOffset 	= DATA_OFFSET;

	/**
	 * An embedded clip-art gate found in a page's raw bytes: the graphics
	 * escape ($10 width heightLSB heightHSB record) that marks where a picture
	 * is placed.  The picture's pixel bytes live in the VLIR record numbered
	 * by {@link #record} (records 64+ are the document's "photo scraps").
	 */
	public static final class ClipRef
	{
		public final int	lineIndex;	// 0-based line of the page this picture precedes
		public final int	record;		// VLIR record holding the picture/scrap bytes
		public final int	width;		// picture width in pixels
		public final int	height;		// picture height in pixels

		ClipRef(final int lineIndex, final int record, final int width, final int height)
		{
			this.lineIndex = lineIndex;
			this.record = record;
			this.width = width;
			this.height = height;
		}
	}

	/**
	 * A single page branch from the VLIR: its raw bytes exactly as stored,
	 * the decoded text of the page, and a map of where each text line
	 * begins inside the raw branch bytes.  All fields are produced by the
	 * single walk in parsePage().
	 */
	private static final class GWPage
	{
		final int			branch;
		final byte[]		raw;
		final int			textStart;
		final int			eopPos;
		final int			contentEnd;
		final int[]			lineStarts;
		final String		text;
		final List<ClipRef> pictures;

		GWPage(final int branch, final byte[] raw, final int textStart, final int eopPos,
				final int contentEnd, final int[] lineStarts, final String text,
				final List<ClipRef> pictures)
		{
			this.branch = branch;
			this.raw = raw;
			this.textStart = textStart;
			this.eopPos = eopPos;
			this.contentEnd = contentEnd;
			this.lineStarts = lineStarts;
			this.text = text;
			this.pictures = pictures;
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
	 * A single line of a page that matched a search pattern, with the
	 * 1-based page and line numbers that REWRITE/DELETE accept verbatim.
	 */
	public static final class LineMatch
	{
		final int page;
		final int line;
		final String text;

		LineMatch(final int page, final int line, final String text)
		{
			this.page = page;
			this.line = line;
			this.text = text;
		}
	}

	/**
	 * A single differing text line between two documents.
	 *
	 * <ul>
	 * <li>{@code '-'} - the line exists in file 1 only (a deletion)</li>
	 * <li>{@code '+'} - the line exists in file 2 only (an addition)</li>
	 * <li>{@code '~'} - the line was changed: the file 1 text at
	 * {@link #line1} was replaced by the file 2 text held by the following
	 * {@code '+'} DiffLine at {@link #line2}</li>
	 * </ul>
	 */
	public static final class DiffLine
	{
		final char kind;
		final int line1;
		final int line2;
		final String text;

		DiffLine(final char kind, final int line1, final int line2, final String text)
		{
			this.kind = kind;
			this.line1 = line1;
			this.line2 = line2;
			this.text = text;
		}
	}

	/**
	 * The comparison result for a single pair of pages (one from each
	 * document) aligned by page number, or a lone page present in only one
	 * of the two documents.
	 */
	public static final class PageDiff
	{
		public static final int IDENTICAL		= 0;
		public static final int DIFFERS			= 1;
		public static final int ONLY_IN_FILE1	= 2;
		public static final int ONLY_IN_FILE2	= 3;

		final int 			 pageNum; // 1-based, in the shared page numbering
		final int 			 status; // one of the constants above
		final int 			 lines1; // real text lines in file 1's page
		final int 			 lines2; // real text lines in file 2's page
		final List<DiffLine> lines; // empty unless status==DIFFERS

		PageDiff(final int pageNum, final int status, final int lines1, final int lines2,
				final List<DiffLine> lines)
		{
			this.pageNum = pageNum;
			this.status = status;
			this.lines1 = lines1;
			this.lines2 = lines2;
			this.lines = lines;
		}

		/**
		 * The number of distinct text changes on the page, where each
		 * added, removed, or replaced line counts once.
		 * @return the change count
		 */
		int countChanges()
		{
			int cost = 0;
			for(int i = 0; i < lines.size(); i++)
			{
				final DiffLine dl = lines.get(i);
				if(dl.kind == '~')
				{
					cost++;
					i++; // skip the paired '+'
				}
				else
				if((dl.kind == '-')||(dl.kind == '+'))
					cost++;
			}
			return cost;
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
		this.geoMod = GeoMod.fromFile(file);
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
		return data.length >= 58 && GeoMod.isCvt(data);
	}

	/**
	 * Check whether a GEOS .CVT file actually holds a GeoWrite document
	 * rather than some other GEOS VLIR/SEQ file masked as a CVT.  GeoWrite
	 * documents identify themselves in block 1 (the GEOS information sector)
	 * with the application name/version strings "Write Image" and "geoWrite".
	 *
	 * @param data full file bytes
	 * @return true if this is a GeoWrite document
	 */
	private static boolean isGeoWriteDocument(final byte[] data)
	{
		if(data.length < (BLOCK_SIZE * 2))
			return false;
		final String block1 = new String(data, BLOCK_SIZE, BLOCK_SIZE, java.nio.charset.StandardCharsets.ISO_8859_1);
		return block1.contains(CVT_GEOAUTHOR)||block1.contains(CVT_GEOIMAGE);
	}

	/**
	 * Check the GEOS file structure byte in block 1 (offset 0x44 within the
	 * information sector): 1 = VLIR, 0 = sequential.
	 *
	 * @param data full file bytes
	 * @param seqFallback value to return when the structure byte is absent or malformed
	 * @return true if the file is VLIR-structured
	 */
	private static boolean isVlirStructure(final byte[] data, final boolean seqFallback)
	{
		final int structByte = data.length >= (BLOCK_SIZE + 0x45) ? (data[BLOCK_SIZE + 0x44] & 0xff) : -1;
		if(structByte == 1) 
			return true;
		if(structByte == 0) 
			return false;
		return seqFallback; // malformed/absent header: caller decides
	}

	/**
	 * Walk a single page branch once, producing both its decoded text and
	 * a map of where every line of that text begins and ends in the raw
	 * bytes, so that LIST line numbers and DELETE line ranges can never
	 * disagree.
	 * 	 
	 * @param branch the VLIR branch/record number of the page
	 * @param raw the page branch bytes exactly as stored
	 * @param declaredLen the content length declared by the VLIR entry
	 * @return GWPage the decoded page
	 */
	private GWPage parsePage(final int branch, final byte[] raw, final int declaredLen)
	{
		final StringBuilder str = new StringBuilder(raw.length);
		// find the NEWCARDSET code following the initial ruler escape
		int i = 1;
		while((i < 35)&&(i < raw.length))
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
		final List<ClipRef> pictures = new ArrayList<ClipRef>();
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
			if(b == 0x10) // graphics escape: $10 width heightLSB heightHSB record
			{
				if(i + 4 < raw.length)
				{
					final int width = raw[i + 1] & 0xff;
					final int height = (raw[i + 2] & 0xff) | ((raw[i + 3] & 0xff) << 8);
					final int record = raw[i + 4] & 0xff;
					pictures.add(new ClipRef(starts.size() - 1, record, width, height));
				}
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
			if((b >= 0x20)&&(b <= 0x7E)) // plain ASCII
			{
				str.append((char)b);
				i++;
			}
			else
				i++; // GEOS "0=do not use"/word-term codes (>=0x80, $A0, $C1-$DA PETSCII): rendered by real GeoWrite readers as nothing
		}
		final int eopPos = (eop < 0) ? raw.length : eop;
		int ce = Math.min(Math.max(declaredLen, 0), raw.length);
		if((eop >= 0)&&(eop + 1 > ce))
			ce = eop + 1;
		starts.add(Integer.valueOf(eopPos));
		final int[] lineStarts = new int[starts.size()];
		for(int x = 0; x < starts.size(); x++)
			lineStarts[x] = starts.get(x).intValue();
		return new GWPage(branch, raw, textStart, eopPos, ce, lineStarts, str.toString(), pictures);
	}

	/**
	 * Parse the file data, extracting all pages while preserving the raw
	 * file bytes, the VLIR sector, every page branch as stored, and the
	 * offset where the non-page branch data (header/footer/photo scraps)
	 * begins, so that the document can later be reconstructed.
	 *
	 * @param in the stream holding the .CVT data
	 * @throws IOException on read errors or if the data is not a GeoWrite document
	 */
	private void parse(final InputStream in) throws IOException
	{
		parse(readAll(in));
	}

	/**
	 * Parse the given raw .CVT file data, resetting all internal state.
	 *
	 * @param data the raw .CVT file bytes
	 * @throws IOException if the data is too short or is not a GeoWrite document
	 */
	private void parse(final byte[] data) throws IOException
	{
		if(data.length < DATA_OFFSET)
			throw new IOException("Not a valid GeoWrite CVT file (too short): "+fileName);
		if(!isCvt(data))
			throw new IOException("Not a GeoWrite CVT file (bad signature): "+fileName);
		if(!isGeoWriteDocument(data))
			throw new IOException("Not a GeoWrite document (GEOS "+fileName+" is not a GeoWrite file)");
		if(!isVlirStructure(data, true))
			throw new IOException("Sequential GEOS file "+fileName+": not a GeoWrite document");
		this.rawFile = data;
		this.vlirSector = Arrays.copyOfRange(data, VLIR_SECTOR_OFF, VLIR_SECTOR_OFF + BLOCK_SIZE);
		if(this.geoMod == null)
			this.geoMod = new GeoMod(null, data);
		this.rawPages.clear();
		int offset = DATA_OFFSET;
		for(int branch = 0; branch < MAX_PAGES; branch++)
		{
			final int numBlocks = vlirSector[branch * 2] & 0xff;
			final int extra = vlirSector[(branch * 2) + 1] & 0xff;
			if((numBlocks == 0)&&(extra == VLIR_NULL_EXTRA)) // null (unused) branch
				continue;
			if((numBlocks == 0)&&(extra == 0)) // end of VLIR sector
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
		for(final GWPage page : rawPages)
			texts.add(page.text);
		return texts;
	}

	/**
	 * Get the embedded clip-art gates of a single page, in source order.
	 * @param pageNum zero-based page index
	 * @return the page's picture references
	 */
	public List<ClipRef> getPagePictures(final int pageNum)
	{
		return rawPages.get(pageNum).pictures;
	}

	/**
	 * Fetch the raw bytes of a VLIR record/branch by its record number.
	 * Picture gates reference records by this number (64+ are photo scraps).
	 * @param branch the VLIR record index
	 * @return the record bytes, or null if the record is absent
	 */
	public byte[] getRecordData(final int branch)
	{
		if((geoMod == null)||(branch < 0))
			return null;
		for(final GeoMod.RawRecord rec : geoMod.getRecords())
		{
			if(rec.index == branch)
			{
				final int len = Math.min(rec.getLength(), rec.raw.length);
				return (len <= 0) ? null : Arrays.copyOf(rec.raw, len);
			}
		}
		return null;
	}

	/**
	 * The number of text lines on a single page, as numbered by LIST.
	 * @param pageNum 1-based page number
	 * @return the line count of that page
	 */
	public int getNumLines(final int pageNum)
	{
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		return rawPages.get(pageNum - 1).getNumLines();
	}

	/**
	 * Compare this document against another document page by page,
	 * producing a textual diff of the decoded page contents.  Pairs are
	 * aligned by page number; a page present in only one document is
	 * reported as {@link PageDiff#ONLY_IN_FILE1} or
	 * {@link PageDiff#ONLY_IN_FILE2}.  Header/footer/photo scrap content is
	 * ignored.
	 *
	 * @param other the document to compare against
	 * @return one PageDiff per page, in page order, aligned to the longer of the two documents
	 */
	public List<PageDiff> diff(final GeoRWriter other)
	{
		final int max = Math.max(rawPages.size(), other.rawPages.size());
		final List<PageDiff> diffs = new ArrayList<PageDiff>(max);
		for(int p = 0; p < max; p++)
		{
			final int pageNum = p + 1;
			if(p >= rawPages.size())
				diffs.add(new PageDiff(pageNum, PageDiff.ONLY_IN_FILE2, 0, countLines(other.rawPages.get(p)), Collections.<DiffLine>emptyList()));
			else
			if(p >= other.rawPages.size())
				diffs.add(new PageDiff(pageNum, PageDiff.ONLY_IN_FILE1, countLines(rawPages.get(p)), 0, Collections.<DiffLine>emptyList()));
			else
			{
				final List<String> l1 = pageTextLines(rawPages.get(p));
				final List<String> l2 = pageTextLines(other.rawPages.get(p));
				if(l1.equals(l2))
					diffs.add(new PageDiff(pageNum, PageDiff.IDENTICAL, l1.size(), l2.size(), Collections.<DiffLine>emptyList()));
				else
					diffs.add(new PageDiff(pageNum, PageDiff.DIFFERS, l1.size(), l2.size(), diffLines(l1, l2)));
			}
		}
		return diffs;
	}

	/**
	 * The real text lines of a page, decoded, excluding the phantom empty
	 * trailing line that a carriage return immediately before the EOP
	 * parses as.
	 *
	 * @param p the page
	 * @return the page's text lines
	 */
	private static List<String> pageTextLines(final GWPage p)
	{
		final List<String> lines = new ArrayList<String>();
		for(final String line : p.text.split("\n", -1))
			lines.add(line);
		final int real = countLines(p);
		while(lines.size() > real)
			lines.remove(lines.size() - 1);
		return lines;
	}

	/**
	 * Diff two lists of text lines using a longest-common-subsequence
	 * walk, then merge a removed line immediately followed by an added line
	 * into a single changed-pair.  Removed lines carry their own 1-based
	 * line number, added lines their target 1-based line number.
	 *
	 * @param a the file 1 lines
	 * @param b the file 2 lines
	 * @return the diff stream as described
	 */
	private static List<DiffLine> diffLines(final List<String> a, final List<String> b)
	{
		final int n = a.size();
		final int m = b.size();
		final int[][] len = new int[n + 1][m + 1];
		for(int i = n - 1; i >= 0; i--)
		{
			for(int j = m - 1; j >= 0; j--)
			{
				len[i][j] = a.get(i).equals(b.get(j))
						? (len[i + 1][j + 1] + 1)
						: Math.max(len[i + 1][j], len[i][j + 1]);
			}
		}
		final List<DiffLine> raw = new ArrayList<DiffLine>(n + m);
		int i = 0;
		int j = 0;
		while((i < n)&&(j < m))
		{
			if(a.get(i).equals(b.get(j)))
			{
				i++;
				j++;
			}
			else
			if(len[i + 1][j] >= len[i][j + 1])
				raw.add(new DiffLine('-', i + 1, 0, a.get(i++)));
			else
				raw.add(new DiffLine('+', 0, j + 1, b.get(j++)));
		}
		while(i < n)
			raw.add(new DiffLine('-', i + 1, 0, a.get(i++)));
		while(j < m)
			raw.add(new DiffLine('+', 0, j + 1, b.get(j++)));

		// merge removed lines directly followed by added lines into
		// changed-pairs: the last removed and the first added become '~'/'+'
		final List<DiffLine> out = new ArrayList<DiffLine>(raw.size());
		int k = 0;
		while(k < raw.size())
		{
			if(raw.get(k).kind != '-')
			{
				out.add(raw.get(k));
				k++;
				continue;
			}
			final int rStart = k;
			while((k < raw.size())&&(raw.get(k).kind == '-'))
				k++;
			final int aStart = k;
			while((k < raw.size())&&(raw.get(k).kind == '+'))
				k++;
			final int r = aStart - rStart;
			final int addCount = k - aStart;
			final int pair = Math.min(r, addCount);
			final int keep = r - pair;
			for(int x = 0; x < keep; x++)
				out.add(raw.get(rStart + x));
			for(int x = 0; x < pair; x++)
			{
				final DiffLine rem = raw.get(rStart + keep + x);
				final DiffLine add = raw.get(aStart + x);
				out.add(new DiffLine('~', rem.line1, add.line2, rem.text));
				out.add(new DiffLine('+', 0, add.line2, add.text));
			}
			for(int x = aStart + pair; x < k; x++)
				out.add(raw.get(x));
		}
		return out;
	}

	/**
	 * Search every page's decoded text for lines matching the given
	 * regular expression.  Matching is line-based (the pattern is applied
	 * to each line independently, like grep), and case-insensitive by
	 * default; prefix the pattern with {@code (?-i)} to make it
	 * case-sensitive.  Line numbers returned align exactly with those
	 * accepted by REWRITE/DELETE/INSERT.
	 *
	 * @param regex the regular expression to match
	 * @return an ordered list of matching lines, page-major then line-minor
	 * @throws PatternSyntaxException if regex is malformed
	 */
	public List<LineMatch> search(final String regex)
	{
		final List<LineMatch> results = new ArrayList<LineMatch>();
		final Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		for(int i = 0; i < rawPages.size(); i++)
			searchPage(p, i, results);
		return results;
	}

	/**
	 * Search a single page's decoded text for lines matching the given
	 * regular expression.  See {@link #search(String)} for matching rules.
	 *
	 * @param regex the regular expression to match
	 * @param pageNum 1-based page number to search
	 * @return an ordered list of matching lines on that page
	 * @throws PatternSyntaxException if regex is malformed
	 */
	public List<LineMatch> search(final String regex, final int pageNum)
	{
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		final Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		final List<LineMatch> results = new ArrayList<LineMatch>();
		searchPage(p, pageNum - 1, results);
		return results;
	}

	/**
	 * Walk a single page's decoded text line by line and collect the
	 * lines matching the given pattern.
	 *
	 * @param p the compiled pattern
	 * @param pageIdx zero-based page index
	 * @param results the list to append matches to
	 */
	private void searchPage(final Pattern p, final int pageIdx, final List<LineMatch> results)
	{
		final String text = rawPages.get(pageIdx).text;
		final String[] lines = text.split("\n", -1);
		for(int line = 1; line <= lines.length; line++)
		{
			final String lineText = lines[line - 1];
			if(p.matcher(lineText).find())
				results.add(new LineMatch(pageIdx + 1, line, lineText));
		}
	}

	/**
	 * Replace all occurrences of a regular expression within the text of
	 * every line that matches it, across all pages, then repair the VLIR
	 * sector and regenerate the source .CVT file, preserving everything
	 * else in the document exactly as it was stored.
	 *
	 * Matching is line-based and case-insensitive by default, exactly like
	 * {@link #search(String)}; prefix the pattern with {@code (?-i)} to
	 * make it case-sensitive.  Only the matched portions of matched lines
	 * change: each such line is rewritten with the replacement applied, the
	 * same way REWRITE rewrites a whole line.  The replacement may contain
	 * $1-style backreferences to the pattern's groups, but not a newline.
	 * Lines that do not match, and everything outside the page branches,
	 * are left byte-for-byte as stored.
	 *
	 * @param regex the regular expression to match
	 * @param replacement the replacement text (per line, all occurrences)
	 * @return the number of replacements actually applied
	 * @throws IOException on read/write errors, if this was read from a
	 *                     stream, or if the replacement text is invalid
	 * @throws PatternSyntaxException if regex is malformed
	 */
	public int replace(final String regex, final String replacement) throws IOException
	{
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");
		return replacePage(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement, -1);
	}

	/**
	 * Replace all occurrences of a regular expression within the text of
	 * every line that matches it on a single page.  See
	 * {@link #replace(String,String)} for matching and encoding rules.
	 *
	 * @param regex the regular expression to match
	 * @param replacement the replacement text (per line, all occurrences)
	 * @param pageNum 1-based page number to replace on
	 * @return the number of replacements actually applied
	 * @throws IOException on read/write errors, if this was read from a
	 *                     stream, or if the replacement text is invalid
	 * @throws PatternSyntaxException if regex is malformed
	 */
	public int replace(final String regex, final String replacement, final int pageNum) throws IOException
	{
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");
		return replacePage(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement, pageNum - 1);
	}

	/**
	 * Apply a compiled pattern to one page or the whole document, editing
	 * the in-memory page contents and rewriting the source file once if
	 * anything changed.  See {@link #replace(String,String)} for the
	 * matching, encoding, and error rules.
	 *
	 * @param p the compiled pattern
	 * @param replacement the replacement text
	 * @param onlyIdx zero-based page index, or -1 for all pages
	 * @return the number of replacements actually applied
	 * @throws IOException if the replacement text is invalid
	 */
	private int replacePage(final Pattern p, final String replacement, final int onlyIdx) throws IOException
	{
		if(replacement.indexOf('\n') >= 0)
			throw new IOException("Replacement text may not contain a newline");
		int total = 0;
		final List<GWPage> pages = new ArrayList<GWPage>(rawPages);
		final int from = (onlyIdx < 0) ? 0 : onlyIdx;
		final int to = (onlyIdx < 0) ? pages.size() - 1 : onlyIdx;
		for(int idx = from; idx <= to; idx++)
		{
			final GWPage page = pages.get(idx);
			byte[] pageRaw = page.raw;
			int contentEnd = page.contentEnd;
			GWPage cur = page;
			final String[] textLines = page.text.split("\n", -1);
			final int maxLine = Math.min(textLines.length, cur.getNumLines());
			for(int line = 1; line <= maxLine; line++)
			{
				if(cur.lineStarts[line - 1] == cur.lineStarts[line])
					continue; // zero-width phantom trailing line
				final String lineText = textLines[line - 1];
				final Matcher m = p.matcher(lineText);
				int occ = 0;
				while(m.find())
					occ++;
				if(occ == 0)
					continue;
				final String newText;
				try
				{
					newText = m.replaceAll(replacement);
				}
				catch(final IllegalArgumentException e)
				{
					throw new IOException("Invalid replacement text: "+e.getMessage());
				}
				if(newText.equals(lineText))
					continue; // matched but replaced nothing (zero-width match)
				final int start = cur.lineStarts[line - 1];
				final int end = cur.lineStarts[line];
				final byte[] enc = (newText + "\r").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
				final int delta = enc.length - (end - start);
				final byte[] newRaw = new byte[pageRaw.length + delta];
				System.arraycopy(pageRaw, 0, newRaw, 0, start);
				System.arraycopy(enc, 0, newRaw, start, enc.length);
				System.arraycopy(pageRaw, end, newRaw, start + enc.length, pageRaw.length - end);
				pageRaw = newRaw;
				contentEnd += delta;
				cur = parsePage(page.branch, newRaw, contentEnd);
				total += occ;
			}
			if(cur != page)
				pages.set(idx, cur);
		}
		if(total == 0)
			return 0;
		writePages(pages);
		return total;
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
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		final GWPage page = rawPages.get(pageNum - 1);
		final int numLines = page.getNumLines();
		if((lineFrom < 1)||(lineTo < lineFrom)||(lineTo > numLines))
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
		final GWPage newPage = parsePage(page.branch, newRaw, newContentEnd);
		final boolean removePage = newPage.isEmptyText()||(newContentEnd <= newPage.textStart);

		// Delegate VLIR repair + file rewrite to shared path in GeoMod via writePages()
		final List<GWPage> pages = new ArrayList<>(rawPages.size());
		for(int i = 0; i < rawPages.size(); i++)
		{
			if(i == pageNum - 1)
			{
				if(!removePage) 
					pages.add(newPage);
			} 
			else
				pages.add(rawPages.get(i));
		}
		
		writePages(pages);
		return removePage;
	}

	public boolean rewriteLine(final int pageNum, final int lineNum, final String text) throws IOException
	{
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		final GWPage page = rawPages.get(pageNum - 1);
		final int numLines = page.getNumLines();
		if((lineNum < 1)||(lineNum > numLines))
			throw new IndexOutOfBoundsException("Line "+lineNum+" out of range (1-"+numLines+")");
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");

		final int start = page.lineStarts[lineNum - 1];
		final int end = page.lineStarts[lineNum];
		final int lineLen = end - start;
		final byte[] raw = page.raw;
		byte[] replacement = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		byte[] replWithCr = new byte[replacement.length + 1];
		System.arraycopy(replacement, 0, replWithCr, 0, replacement.length);
		replWithCr[replacement.length] = 13;
		replacement = replWithCr;
		final int replLen = replacement.length;
		final byte[] finalRaw = new byte[raw.length + replLen - lineLen];
		int dst = 0;
		System.arraycopy(raw, 0, finalRaw, dst, start); dst += start;
		System.arraycopy(replacement, 0, finalRaw, dst, replLen); dst += replLen;
		System.arraycopy(raw, end, finalRaw, dst, raw.length - end);
		final int delta = replLen - lineLen;
		final int newContentEnd = page.contentEnd + delta;
		if(newContentEnd < page.textStart)
			return deleteLines(pageNum, lineNum, lineNum);
		final GWPage newPage = parsePage(page.branch, finalRaw, newContentEnd);
		final boolean removePage = newPage.isEmptyText()||(newContentEnd <= newPage.textStart);

		// Delegate VLIR repair + file rewrite to shared path in GeoMod via writePages()
		final List<GWPage> pages = new ArrayList<>(rawPages.size());
		for(int i = 0; i < rawPages.size(); i++)
		{
			if(i == pageNum - 1)
			{
				if(!removePage) 
					pages.add(newPage);
			} 
			else
				pages.add(rawPages.get(i));
		}
		
		writePages(pages);
		return removePage;
	}

	/**
	 * Split a text string into its logical lines and encode each as raw
	 * GeoWrite bytes terminated by a carriage return ($0D).  An empty text
	 * yields a single blank (just a carriage return) line.
	 *
	 * @param text the text to encode, '\n' separates lines
	 * @return the raw page bytes for the inserted lines, ending in $0D
	 */
	private static byte[] textToLines(final String text)
	{
		final String[] lines = text.split("\n", -1);
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		for(final String line : lines)
		{
			final byte[] raw = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
			bout.write(raw, 0, raw.length);
			bout.write(0x0D);
		}
		return bout.toByteArray();
	}

	/**
	 * Insert one or more lines of text into a page, before the given line,
	 * then repair the VLIR sector and regenerate the source .CVT file,
	 * preserving everything else in the document exactly as it was stored.
	 *
	 * The inserted text is treated as plain ASCII/PETSCII; embedded '\n'
	 * characters create additional lines on the page.
	 *
	 * If the insertion pushes a page that previously held at most
	 * {@value #MAX_LINES_PER_PAGE} lines past that count, the surplus lines
	 * are flowed onto the following page(s), and a new page is created if
	 * the document grows past the last page.  Pages that already exceed the
	 * count (dense source documents) are left untouched and simply absorb
	 * any overflow, matching how those documents were originally laid out.
	 *
	 * @param pageNum 1-based page number
	 * @param lineNum 1-based line before which the text is inserted; may be
	 *                numLines+1 to append after the last line
	 * @param text the text to insert
	 * @return the number of lines inserted
	 * @throws IOException on read/write errors, if this was read from a stream,
	 *                     or if the document would exceed the VLIR page limit
	 */
	public int insertLines(final int pageNum, final int lineNum, final String text) throws IOException
	{
		if((pageNum < 1)||(pageNum > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+rawPages.size()+")");
		final GWPage page = rawPages.get(pageNum - 1);
		final int numLines = page.getNumLines();
		if((lineNum < 1)||(lineNum > numLines + 1))
			throw new IndexOutOfBoundsException("Line "+lineNum+" out of range (1-"+(numLines+1)+")");
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");

		final int start = page.lineStarts[lineNum - 1];
		byte[] replacement = textToLines(text);
		// When appending after a page whose final line is not CR-terminated
		// (the raw ends in the line's last text byte, or padding, or a lone
		// EOP), insert a CR before the replacement so the appended text
		// begins its own line instead of joining the final line.
		if((lineNum == numLines + 1)&&(start > page.textStart)&&(page.raw[start - 1] & 0xff) != 0x0D)
		{
			final byte[] crText = new byte[replacement.length + 1];
			crText[0] = 0x0D;
			System.arraycopy(replacement, 0, crText, 1, replacement.length);
			replacement = crText;
		}
		final int replLen = replacement.length;
		final int inserted = replacement.length == 0 ? 0 : text.split("\n", -1).length;
		final byte[] finalRaw = new byte[page.raw.length + replLen];
		System.arraycopy(page.raw, 0, finalRaw, 0, start);
		System.arraycopy(replacement, 0, finalRaw, start, replLen);
		System.arraycopy(page.raw, start, finalRaw, start + replLen, page.raw.length - start);
		final int newContentEnd = page.contentEnd + replLen;

		final List<GWPage> pages = new ArrayList<GWPage>(rawPages);
		final int[] before = new int[rawPages.size()];
		for(int b = 0; b < rawPages.size(); b++)
			before[b] = countLines(rawPages.get(b));
		final byte[] insertContent = Arrays.copyOf(finalRaw, newContentEnd);
		pages.set(pageNum - 1, parsePage(page.branch, insertContent, insertContent.length));

		// Only reflow (split onto new/next pages) when a page that was small
		// enough to fit now has too many lines.  Dense pages are left alone.
		if((before[pageNum - 1] <= MAX_LINES_PER_PAGE)
		&&(countLines(pages.get(pageNum - 1)) > MAX_LINES_PER_PAGE))
			reflow(pages, before, pageNum - 1);
		writePages(pages);
		return inserted;
	}

	/**
	 * Insert a blank page at the given 1-based position, then repair the
	 * VLIR sector and regenerate the source .CVT file, preserving everything
	 * else in the document exactly as it was stored.
	 *
	 * The new page copies the document's first page header (the initial
	 * ruler escape and NEWCARDSET), or a default header when the document
	 * has no pages, followed by an EOP terminator, so it parses as a page
	 * with no text lines.  Pages after the insertion point shift down by
	 * one, matching how DELETE shifts pages up.
	 *
	 * @param pageNum 1-based position to insert before; page count + 1
	 *                appends at the end of the document
	 * @return the 1-based number of the inserted page
	 * @throws IOException on read/write errors, if this was read from a
	 *                     stream, or if the document would exceed the VLIR
	 *                     page limit
	 */
	public int insertBlankPage(final int pageNum) throws IOException
	{
		if((pageNum < 1)||(pageNum > rawPages.size() + 1))
			throw new IndexOutOfBoundsException("Page "+pageNum+" out of range (1-"+(rawPages.size()+1)+")");
		if(rawPages.size() >= MAX_PAGES)
			throw new IOException("Document would exceed the "+MAX_PAGES+" page VLIR limit");
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");

		final byte[] header;
		if(rawPages.isEmpty())
		{
			// default ruler escape (27 bytes) + NEWCARDSET (4 bytes)
			header = new byte[31];
			header[0] = 0x11;
			header[27] = 0x17;
		}
		else
			header = Arrays.copyOf(rawPages.get(0).raw, rawPages.get(0).textStart);
		final byte[] blank = new byte[header.length + 1];
		System.arraycopy(header, 0, blank, 0, header.length);
		blank[blank.length - 1] = 0x0C; // EOP
		final List<GWPage> pages = new ArrayList<GWPage>(rawPages);
		pages.add(pageNum - 1, parsePage(rawPages.size(), blank, blank.length));
		writePages(pages);
		return pageNum;
	}

	/**
	 * Flow surplus lines forward page by page, splitting any page that was
	 * small enough to fit but now holds more than {@value #MAX_LINES_PER_PAGE}
	 * lines onto the following page, and creating a new page when the last
	 * page overflows.  Dense pages (those already over the cap before the
	 * edit) are never split; they simply absorb incoming overflow.
	 *
	 * @param pages the ordered list of page contents (content-only raws); modified in place
	 * @param before the line count of each page before the edit began; extended in place as new pages are created
	 * @param startIdx index of the page that was just edited
	 * @throws IOException if the document would exceed the VLIR page limit
	 */
	private void reflow(final List<GWPage> pages, int[] before, final int startIdx) throws IOException
	{
		int cur = startIdx;
		while(cur < pages.size())
		{
			final GWPage p = pages.get(cur);
			final int n = countLines(p);
			final boolean wasSmall = before[cur] <= MAX_LINES_PER_PAGE;
			if(wasSmall&&(n > MAX_LINES_PER_PAGE))
			{
				final int boundary = p.lineStarts[MAX_LINES_PER_PAGE]; // start of line cap+1
				final int eop = p.eopPos;
				// lines to move on: content from the start of line cap+1 up to the EOP
				final byte[] tail = Arrays.copyOfRange(p.raw, boundary, Math.min(eop, p.raw.length));
				// keep page = header + first cap lines + its EOP terminator
				final byte[] keep = buildKeepPage(p, boundary);
				pages.set(cur, parsePage(p.branch, keep, keep.length));
				if(cur + 1 < pages.size())
				{
					final GWPage next = pages.get(cur + 1);
					final byte[] nb = next.raw;
					final int ts = next.textStart;
					final byte[] newNext = new byte[ts + tail.length + (nb.length - ts)];
					System.arraycopy(nb, 0, newNext, 0, ts);
					System.arraycopy(tail, 0, newNext, ts, tail.length);
					System.arraycopy(nb, ts, newNext, ts + tail.length, nb.length - ts);
					pages.set(cur + 1, parsePage(next.branch, newNext, newNext.length));
				}
				else
				{
					if(pages.size() >= MAX_PAGES)
						throw new IOException("Document too long: would exceed the "+MAX_PAGES+" page VLIR limit");
					// new page: copy the overflowed page's header, then the moved lines, then an EOP
					final byte[] head = Arrays.copyOf(p.raw, p.textStart);
					final byte[] newPage = new byte[head.length + tail.length + 1];
					System.arraycopy(head, 0, newPage, 0, head.length);
					System.arraycopy(tail, 0, newPage, head.length, tail.length);
					newPage[newPage.length - 1] = 0x0C; // EOP
					pages.add(parsePage(pages.size(), newPage, newPage.length));
					final int[] ext = new int[before.length + 1];
					System.arraycopy(before, 0, ext, 0, before.length);
					ext[ext.length - 1] = 0; // a fresh page counts as small
					before = ext;
				}
			}
			cur++;
		}
	}

	/**
	 * Build the raw bytes of a page after a split: everything up to the line
	 * boundary (header plus the kept lines and their terminators), followed by
	 * the original EOP terminator so the resulting page stays well-formed.
	 *
	 * @param p the page being split
	 * @param boundary offset of the first line being moved away
	 * @return the kept page's content bytes
	 */
	private static byte[] buildKeepPage(final GWPage p, final int boundary)
	{
		final int eop = Math.min(p.eopPos, p.raw.length);
		final byte[] keep;
		if(eop < p.raw.length)
		{
			// retain the single EOP byte
			keep = new byte[boundary + 1];
			System.arraycopy(p.raw, 0, keep, 0, boundary);
			keep[boundary] = p.raw[eop];
		}
		else
		{
			keep = Arrays.copyOf(p.raw, boundary);
		}
		return keep;
	}

	/**
	 * The number of real text lines on a page.  GeoWrite terminates a page
	 * with an EOP ($0C) that, when the last line is CR-terminated, parses as
	 * an extra zero-width trailing line; that phantom is excluded here so the
	 * page-fill cap counts actual text lines only.
	 *
	 * @param p the page
	 * @return the count of real text lines
	 */
	private static int countLines(final GWPage p)
	{
		final int n = p.getNumLines();
		if((n > 0)&&(p.lineStarts[n - 1] == p.lineStarts[n]))
			return n - 1;
		return n;
	}

	/**
	 * The decoded text of a contiguous block of lines on a page, joined
	 * with '\n' separators, ready to be handed to INSERT (and therefore
	 * COPY/MOVE) verbatim.
	 *
	 * @param pageNum 1-based page number
	 * @param lineFrom 1-based first line of the block
	 * @param lineTo 1-based last line of the block (inclusive)
	 * @return the joined block text
	 */
	private String blockText(final int pageNum, final int lineFrom, final int lineTo)
	{
		final GWPage page = rawPages.get(pageNum - 1);
		final String[] lines = page.text.split("\n", -1);
		final StringBuilder str = new StringBuilder();
		for(int i = lineFrom - 1; i < lineTo; i++)
		{
			if(i > lineFrom - 1)
				str.append('\n');
			str.append(lines[i]);
		}
		return str.toString();
	}

	/**
	 * Validate the page and line arguments shared by COPY and MOVE.
	 *
	 * @param srcPage 1-based source page number
	 * @param lineFrom 1-based first line of the block
	 * @param lineTo 1-based last line of the block (inclusive)
	 * @param dstPage 1-based destination page number
	 * @param dstLine 1-based destination line; may be the destination page's
	 *                line count + 1 to append after its last line
	 */
	private void validateBlock(final int srcPage, final int lineFrom, final int lineTo,
			final int dstPage, final int dstLine)
	{
		if((srcPage < 1)||(srcPage > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+srcPage+" out of range (1-"+rawPages.size()+")");
		final int srcLines = rawPages.get(srcPage - 1).getNumLines();
		if((lineFrom < 1)||(lineTo < lineFrom)||(lineTo > srcLines))
			throw new IndexOutOfBoundsException("Lines "+lineFrom+"-"+lineTo+" out of range (1-"+srcLines+")");
		if((dstPage < 1)||(dstPage > rawPages.size()))
			throw new IndexOutOfBoundsException("Page "+dstPage+" out of range (1-"+rawPages.size()+")");
		final int dstLines = rawPages.get(dstPage - 1).getNumLines();
		if((dstLine < 1)||(dstLine > dstLines + 1))
			throw new IndexOutOfBoundsException("Line "+dstLine+" out of range (1-"+(dstLines+1)+")");
	}

	/**
	 * Copy a contiguous block of lines from one page and insert them before
	 * a given line on the same or another page, then rewrite the source .CVT
	 * file, preserving everything else exactly as stored.
	 *
	 * The block is re-encoded from its decoded text, exactly as if it had
	 * been typed with INSERT: PETSCII text becomes ASCII, inline ruler and
	 * graphics escapes are dropped.  If the insertion makes a page that
	 * previously held at most {@value #MAX_LINES_PER_PAGE} lines overflow,
	 * the surplus lines are flowed onto following pages as INSERT does.
	 *
	 * @param srcPage 1-based page containing the block
	 * @param lineFrom 1-based first line of the block
	 * @param lineTo 1-based last line of the block (inclusive)
	 * @param dstPage 1-based page to insert into
	 * @param dstLine 1-based line before which to insert; may be the page's
	 *                line count + 1 to append after its last line
	 * @return the number of lines copied
	 * @throws IOException on read/write errors, or if this was read from a stream
	 */
	public int copyLines(final int srcPage, final int lineFrom, final int lineTo,
			final int dstPage, final int dstLine) throws IOException
	{
		validateBlock(srcPage, lineFrom, lineTo, dstPage, dstLine);
		return insertLines(dstPage, dstLine, blockText(srcPage, lineFrom, lineTo));
	}

	/**
	 * Move a contiguous block of lines from one page to before a given line
	 * on the same or another page, then rewrite the source .CVT file,
	 * preserving everything else exactly as stored.  If moving the block
	 * empties the source page, the page itself is deleted (and, as with
	 * DELETE, later page numbers shift down by one).
	 *
	 * The block is re-encoded from its decoded text, exactly as if it had
	 * been typed with INSERT, and any page-overflow reflow is handled by the
	 * underlying INSERT path.  See {@link #copyLines(int,int,int,int,int)}.
	 *
	 * @param srcPage 1-based page containing the block
	 * @param lineFrom 1-based first line of the block
	 * @param lineTo 1-based last line of the block (inclusive)
	 * @param dstPage 1-based page to insert into
	 * @param dstLine 1-based line before which to insert; may be the page's
	 *                line count + 1 to append after its last line
	 * @return the number of lines moved
	 * @throws IOException on read/write errors, or if this was read from a stream
	 */
	public int moveLines(final int srcPage, final int lineFrom, final int lineTo,
			final int dstPage, final int dstLine) throws IOException
	{
		if(srcPage == dstPage)
		{
			if((dstLine > lineFrom)&&(dstLine <= lineTo))
				throw new IllegalArgumentException("Cannot move a block into its own interior");
		}
		validateBlock(srcPage, lineFrom, lineTo, dstPage, dstLine);
		final String text = blockText(srcPage, lineFrom, lineTo);
		final int blockSize = lineTo - lineFrom + 1;

		// Delete the source first: any reflow triggered by the subsequent
		// INSERT only moves lines on pages from the destination onward, so
		// the source range can never be displaced after we stop referring to it.
		final boolean removedPage = deleteLines(srcPage, lineFrom, lineTo);
		int toPage = dstPage;
		int toLine = dstLine;
		if(srcPage == dstPage)
		{
			if(removedPage)
				throw new IllegalArgumentException("Cannot move a whole page into itself");
			if(dstLine > lineTo) // the insert target was after the removed block
				toLine -= blockSize;
		}
		else
		if(removedPage&&(dstPage > srcPage))
			toPage--; // pages after the removed source page have shifted down
		return insertLines(toPage, toLine, text);
	}

	/**
	 * Rewrite the source .CVT file with the given ordered set of pages,
	 * renumbering their VLIR branches 0..N-1, repairing the VLIR sector,
	 * and preserving the header/footer/photo tail exactly as stored.
	 * Delegates to GeoMod's shared binary file management for VLIR repair.
	 *
	 * @param pages the pages to write, in order; each raw holds only content bytes
	 * @throws IOException on write errors, or if this was read from a stream
	 */
	private void writePages(final List<GWPage> pages) throws IOException
	{
		if(sourceFile == null)
			throw new IOException("No source file to rewrite");
		
		// Convert RawPage list to GeoMod.RawRecord for shared VLIR management
		final List<GeoMod.RawRecord> records = new ArrayList<>(pages.size());
		for(int i = 0; i < pages.size(); i++)
		{
			final GWPage p = pages.get(i);
			final int contentEnd = p.contentEnd;
			final int numBlocks = Math.max(1, (contentEnd + BLOCK_SIZE - 1) / BLOCK_SIZE);
			final byte[] stored = (i == pages.size() - 1)&&(tailOffset >= rawFile.length)
				? Arrays.copyOf(p.raw, contentEnd) // last page: trailing tail absent -> keep only content
				: Arrays.copyOf(p.raw, numBlocks * BLOCK_SIZE);
			records.add(GeoMod.RawRecord.fromRawLength(i, stored));
		}
		
		// Delegate VLIR repair + file reconstruction to shared GeoMod method
		geoMod.writePages(records);
		
		// Re-parse rawFile to update internal state
		parse(rawFile);
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
		System.out.println("  GeoRWriter INSERTPAGE [file.cvt] [page]");
		System.out.println("    - Insert a blank page, rewriting the file.");
		System.out.println("  GeoRWriter REWRITE [file.cvt] [page] [line] [text]");
		System.out.println("    - Replace one or more lines of text, rewriting the file.");
		System.out.println("  GeoRWriter REPLACE [file.cvt] [pattern] [replacement] [page]");
		System.out.println("    - Replace all regex matches within matched lines.");
		System.out.println("  GeoRWriter DELETE [file.cvt] [page] [line]-[line]");
		System.out.println("    - Delete one or more lines of text, rewriting the file.");
		System.out.println("  GeoRWriter COPY [file.cvt] [page] [line]-[line] [dstpage] [dstline]");
		System.out.println("    - Copy a block of lines to before a line on any page.");
		System.out.println("  GeoRWriter MOVE [file.cvt] [page] [line]-[line] [dstpage] [dstline]");
		System.out.println("    - Move a block of lines to before a line on any page.");
		System.out.println("  GeoRWriter SEARCH [file.cvt] [pattern] [page]");
		System.out.println("    - Find lines matching a regex, reporting page:line.");
		System.out.println("  GeoRWriter COMPARE [file1.cvt] [file2.cvt]");
		System.out.println("    - Diff two documents page by page (exit 0 if identical, 1 if not).");
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
					if((pageNum < 1)||(pageNum > reader.getNumPages()))
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
				if(args.length < 5) 
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
				final int lineNum;
				try 
				{
					lineNum = Integer.parseInt(args[3]);
				}
				catch(final NumberFormatException e) 
				{
					System.err.println("Error: invalid line number '"+args[3]+"'");
					return;
				}
				StringBuilder sb = new StringBuilder();
				for(int i = 4; i < args.length; i++) 
				{
					if(i > 4) sb.append(' ');
					sb.append(args[i]);
				}
				final String text = sb.toString();
				final GeoRWriter writer = new GeoRWriter(args[1], false);
				if((pageNum < 1)||(pageNum > writer.getNumPages())) 
				{
					System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
					return;
				}
				final int numLines = writer.getNumLines(pageNum);
				if((lineNum < 1)||(lineNum > numLines + 1)) 
				{
					System.err.println("Error: line "+lineNum+" out of range (1-"+(numLines+1)+")");
					return;
				}
				try 
				{
					final int inserted = writer.insertLines(pageNum, lineNum, text);
					System.out.println("Inserted "+inserted+" line(s) before line "+lineNum+" on page "+pageNum+".");
				}
				catch(final IOException e)
				{
					System.err.println("Error: " + e.getMessage());
				}
			}
			else
			if(args[0].equalsIgnoreCase("INSERTPAGE"))
			{
				if(args.length < 2)
				{
					usage();
					return;
				}
				final GeoRWriter writer = new GeoRWriter(args[1], false);
				final int pageNum;
				if(args.length >= 3)
				{
					try
					{
						pageNum = Integer.parseInt(args[2]);
					}
					catch(final NumberFormatException e)
					{
						System.err.println("Error: invalid page number '"+args[2]+"'");
						return;
					}
					if((pageNum < 1)||(pageNum > writer.getNumPages() + 1))
					{
						System.err.println("Error: page "+pageNum+" out of range (1-"+(writer.getNumPages()+1)+")");
						return;
					}
				}
				else
					pageNum = writer.getNumPages() + 1;
				try
				{
					final int inserted = writer.insertBlankPage(pageNum);
					System.out.println("Inserted blank page "+inserted+".");
				}
				catch(final IOException e)
				{
					System.err.println("Error: " + e.getMessage());
				}
			}
			else
			if(args[0].equalsIgnoreCase("REWRITE"))
			{
				if(args.length < 5) 
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
				final int lineNum;
				try 
				{
					lineNum = Integer.parseInt(args[3]);
				}
				catch(final NumberFormatException e) 
				{
					System.err.println("Error: invalid line number '"+args[3]+"'");
					return;
				}
				StringBuilder sb = new StringBuilder();
				for(int i = 4; i < args.length; i++) 
				{
					if(i > 4) sb.append(' ');
					sb.append(args[i]);
				}
				final String text = sb.toString();
				final GeoRWriter writer = new GeoRWriter(args[1], false);
				if((pageNum < 1)||(pageNum > writer.getNumPages())) 
				{
					System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
					return;
				}
				final int numLines = writer.getNumLines(pageNum);
				if((lineNum < 1)||(lineNum > numLines)) 
				{
					System.err.println("Error: line "+lineNum+" out of range (1-"+numLines+")");
					return;
				}
				try 
				{
					writer.rewriteLine(pageNum, lineNum, text);
					System.out.println("Rewrote line "+lineNum+" on page "+pageNum+".");
				} 
				catch(final IOException e) 
				{
					System.err.println("Error: " + e.getMessage());
				}
			}
			else
			if(args[0].equalsIgnoreCase("REPLACE"))
			{
				if(args.length < 4)
				{
					usage();
					return;
				}
				final String regex = args[2];
				final String replacement = args[3];
				final GeoRWriter writer = new GeoRWriter(args[1], false);
				try
				{
					final int count;
					if(args.length >= 5)
					{
						final int pageNum;
						try
						{
							pageNum = Integer.parseInt(args[4]);
						}
						catch(final NumberFormatException e)
						{
							System.err.println("Error: invalid page number '"+args[4]+"'");
							return;
						}
						if((pageNum < 1)||(pageNum > writer.getNumPages()))
						{
							System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
							return;
						}
						count = writer.replace(regex, replacement, pageNum);
					}
					else
						count = writer.replace(regex, replacement);
					if(count == 0)
						System.out.println("No matches found.");
					else
						System.out.println("Replaced "+count+" occurrence"+(count==1?"":"s")+".");
				}
				catch(final PatternSyntaxException e)
				{
					System.err.println("Error: invalid search pattern: "+e.getMessage());
				}
				catch(final IOException e)
				{
					System.err.println("Error: " + e.getMessage());
				}
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
				if((pageNum < 1)||(pageNum > writer.getNumPages()))
				{
					System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
					return;
				}
				final int numLines = writer.getNumLines(pageNum);
				if((lineFrom < 1)||(lineTo < lineFrom)||(lineTo > numLines))
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
			if(args[0].equalsIgnoreCase("COPY")||args[0].equalsIgnoreCase("MOVE"))
			{
				final boolean isMove = args[0].equalsIgnoreCase("MOVE");
				if(args.length < 6)
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
				final int dstPage;
				final int dstLine;
				try
				{
					dstPage = Integer.parseInt(args[4]);
					dstLine = Integer.parseInt(args[5]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid destination page or line");
					return;
				}
				final GeoRWriter writer = new GeoRWriter(args[1],false);
				try
				{
					final int count;
					if(isMove)
						count = writer.moveLines(pageNum, lineFrom, lineTo, dstPage, dstLine);
					else
						count = writer.copyLines(pageNum, lineFrom, lineTo, dstPage, dstLine);
					System.out.println((isMove?"Moved":"Copied")+" "+count+" line(s) from page "+pageNum+", lines "+lineFrom+"-"+lineTo+" to before line "+dstLine+" on page "+dstPage+".");
				}
				catch(final IndexOutOfBoundsException e)
				{
					System.err.println("Error: "+e.getMessage());
				}
			}
			else
			if(args[0].equalsIgnoreCase("SEARCH"))
			{
				if(args.length < 3)
				{
					usage();
					return;
				}
				final String regex = args[2];
				final GeoRWriter writer = new GeoRWriter(args[1], false);
				try
				{
					final List<LineMatch> matches;
					if(args.length >= 4)
					{
						final int pageNum;
						try
						{
							pageNum = Integer.parseInt(args[3]);
						}
						catch(final NumberFormatException e)
						{
							System.err.println("Error: invalid page number '"+args[3]+"'");
							return;
						}
						if((pageNum < 1)||(pageNum > writer.getNumPages()))
						{
							System.err.println("Error: page "+pageNum+" out of range (1-"+writer.getNumPages()+")");
							return;
						}
						matches = writer.search(regex, pageNum);
					}
					else
						matches = writer.search(regex);
					if(matches.isEmpty())
					{
						System.out.println("No matches found.");
						return;
					}
					final Set<Integer> pages = new HashSet<Integer>();
					for(final LineMatch m : matches)
					{
						pages.add(Integer.valueOf(m.page));
						System.out.println("page "+m.page+", line "+m.line+": "+m.text);
					}
					System.out.println(matches.size()+" match"+(matches.size()==1?"":"es")+" on "+pages.size()+" page"+(pages.size()==1?"":"s")+".");
				}
				catch(final PatternSyntaxException e)
				{
					System.err.println("Error: invalid search pattern: "+e.getMessage());
				}
			}
			else
			if(args[0].equalsIgnoreCase("COMPARE"))
			{
				if(args.length < 3)
				{
					usage();
					return;
				}
				try
				{
					final GeoRWriter cmp1 = new GeoRWriter(args[1], false);
					final GeoRWriter cmp2 = new GeoRWriter(args[2], false);
					final List<PageDiff> diffs = cmp1.diff(cmp2);
					int differing = 0;
					int only1 = 0;
					int only2 = 0;
					for(final PageDiff d : diffs)
					{
						if(d.status == PageDiff.DIFFERS)
							differing++;
						else
						if(d.status == PageDiff.ONLY_IN_FILE1)
							only1++;
						else
						if(d.status == PageDiff.ONLY_IN_FILE2)
							only2++;
					}
					System.out.println("Pages: "+cmp1.getNumPages()+" vs "+cmp2.getNumPages()
							+" ("+differing+" differing, "+only1+" only in file1, "+only2+" only in file2)");
					for(final PageDiff d : diffs)
					{
						switch(d.status)
						{
						case PageDiff.IDENTICAL:
							System.out.println("@"+d.pageNum+": identical");
							break;
						case PageDiff.DIFFERS:
							System.out.println("@"+d.pageNum+": differs ("+d.countChanges()+" changes)");
							for(final DiffLine dl : d.lines)
							{
								if(dl.kind == '~')
									System.out.println("  @"+d.pageNum+":"+dl.line1+" ~ "+dl.text);
								else
								if(dl.kind == '+')
									System.out.println("  @"+d.pageNum+":"+dl.line2+" + "+dl.text);
								else
									System.out.println("  @"+d.pageNum+":"+dl.line1+" - "+dl.text);
							}
							break;
						case PageDiff.ONLY_IN_FILE1:
							System.out.println("@"+d.pageNum+": only in file1 ("+d.lines1+" lines)");
							break;
						case PageDiff.ONLY_IN_FILE2:
							System.out.println("@"+d.pageNum+": only in file2 ("+d.lines2+" lines)");
							break;
						}
					}
					System.exit((differing + only1 + only2) == 0 ? 0 : 1);
				}
				catch(final IOException e)
				{
					System.err.println("Error: " + e.getMessage());
					System.exit(2);
				}
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
