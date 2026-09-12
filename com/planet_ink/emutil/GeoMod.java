package com.planet_ink.emutil;

import java.io.*;
import java.util.*;

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
 * Binary page/record editor for GEOS .CVT files (both VLIR and sequential format).
 *
 * A loose .CVT file layout:
 *   block 0 (254 bytes)   synthetic GEOS header w/ "PRG formatted GEOS file V1.0" or "SEQ ..."
 *   block 1 (254 bytes)   GEOS file header from the directory entry(page 0)
 *   block 2 (254 bytes)   VLIR sector - per branch a [numBlocks, extra] pair (VLIR files only)
 *   data blocks            page/record data starting at offset 762
 *
 * For VLIR format: branches 0-127 each map to variable-length records via the VLIR table.
 *   First 64 branches are typically used as "pages". Branch length = numBlocks*254 + extra - 1.
 * For sequential format: blocks after block 1 form a single implicit page (all data).
 *
 * @author BZ
 */
public class GeoMod
{
	public static final int		BLOCK_SIZE			= 254;
	public static final int		VLIR_SECTOR_OFF		= BLOCK_SIZE * 2; // 508
	public static final int		DATA_OFFSET			= BLOCK_SIZE * 3; // 762
	// A 254-byte VLIR sector holds [numBlocks, extra] pairs: 127 entries max (0..126).
	public static final int		MAX_BRANCHES		= BLOCK_SIZE / 2; // 127
	public static final String	CVT_SIGNATURE_PRG	= "PRG formatted GEOS file V1.0";
	public static final String	CVT_SIGNATURE_SEQ	= "SEQ formatted GEOS file V1.0";
	public static final int		VLIR_NULL_EXTRA		= 0xFF;

	/**
	 * A raw record/branch from a VLIR file: its index, raw bytes, and metadata.
	 */
	public static final class RawRecord
	{
		public final int index;
		public final byte[] raw;
		public final int numBlocks;
		public final int extra;
		public final boolean isNull;

		RawRecord(final int index, final byte[] raw, final int numBlocks, final int extra)
		{
			this.index = index;
			this.raw = raw;
			this.numBlocks = numBlocks;
			this.extra = extra;
			this.isNull =((numBlocks == 0)&&(extra == VLIR_NULL_EXTRA));
		}

		/**
		 * Build a record whose VLIR metadata (numBlocks/extra) is derived from
		 * the raw byte length, so getLength() always equals raw.length. Use for
		 * records that have been resized.
		 */
		static RawRecord fromRawLength(final int index, final byte[] raw)
		{
			final int numBlocks = Math.max(1, (raw.length + BLOCK_SIZE - 1) / BLOCK_SIZE);
			final int rem = raw.length % BLOCK_SIZE;
			final int extra = rem == 0 ? 1 : rem + 1;
			return new RawRecord(index, raw, numBlocks, extra);
		}

		int getLength()
		{
			if(isNull)
				return 0;
			// Sequential (non-VLIR) records carry no branch metadata: the raw
			// bytes themselves are the entire record.
			if(numBlocks == 0&&extra == 0)
				return raw.length;
			return (extra <= 1) ? (numBlocks * BLOCK_SIZE) : ((numBlocks - 1) * BLOCK_SIZE + extra - 1);
		}

		public byte[] clone()
		{
			return raw.clone();
		}
	}

	public static final class PageInfo
	{
		public final int index;
		public final String name;
		public final long size;
		public final boolean isNull;
		public final String type; // "VLIR", "SEQ", "HEADER"

		PageInfo(final int index, final String name, final long size, final boolean isNull, final String type)
		{
			this.index = index;
			this.name = name;
			this.size = size;
			this.isNull = isNull;
			this.type = type;
		}
	}

	protected byte[] rawFile;
	protected byte[] headerBlock0; // block 0: synthetic GEOS signature (page "0")
	protected byte[] headerBlock1; // block 1: GEOS directory entry(also page "0" for editing)
	protected boolean isSequential;
	protected List<RawRecord> records = Collections.emptyList();
	protected int tailOffset; // file offset just past the last record (start of any tail/scrap data)

	private final File sourceFile;

	/**
	 * Check whether the data looks like a loose .CVT file by looking for the GEOS signature.
	 */
	public static boolean isCvt(final byte[] data)
	{
		if(data.length < DATA_OFFSET)
			return false;
		return new String(data, 30, CVT_SIGNATURE_PRG.length()).equals(CVT_SIGNATURE_PRG)
			|| new String(data, 30, CVT_SIGNATURE_SEQ.length()).equals(CVT_SIGNATURE_SEQ);
	}

	public static boolean isCvt(final File file)
	{
		try(final InputStream in = new FileInputStream(file)) 
		{
			return isCvt(readAll(in));
		} 
		catch(final IOException e) 
		{
			return false;
		}
	}

	protected GeoMod(final File sourceFile, final byte[] data) throws IOException
	{
		this.sourceFile = sourceFile;
		parse(data);
	}

	public static GeoMod fromFile(final String filename) throws IOException
	{
		return fromFile(new File(filename));
	}

	public static GeoMod fromFile(final File file) throws IOException
	{
		if(!file.exists())
			throw new IOException("File not found: " + file);
		try(final InputStream in = new FileInputStream(file))
		{
			final byte[] data = readAll(in);
			return new GeoMod(file, data);
		}
	}

	private static byte[] readAll(final InputStream in) throws IOException
	{
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int n;
		while((n = in.read(buf)) >= 0)
			bout.write(buf, 0, n);
		return bout.toByteArray();
	}

	private void parse(final byte[] data) throws IOException
	{
		if(data.length < DATA_OFFSET)
			throw new IOException("Not a valid .CVT file (too short): " + (sourceFile == null ? "<stream>" : sourceFile.getName()));
		if(!isCvt(data))
			throw new IOException("Not a GEOS .CVT file (bad signature): " + (sourceFile == null ? "<stream>" : sourceFile.getName()));

		this.rawFile = data;
		this.headerBlock0 = Arrays.copyOfRange(data, 0, BLOCK_SIZE);
		this.headerBlock1 = Arrays.copyOfRange(data, BLOCK_SIZE, BLOCK_SIZE * 2);

		// Detect VLIR vs sequential.  Block 1 (the GEOS information sector)
		// declares the file structure at offset 0x44 (1=VLIR, 0=SEQ); fall
		// back to the sector heuristic only when that byte is malformed.
		final int structByte = data.length >= (BLOCK_SIZE + 0x45) ? (data[BLOCK_SIZE + 0x44] & 0xFF) : -1;
		this.isSequential = (structByte == 0)||(structByte == 1 ? false : !detectVlir(data));

		if(isSequential) 
		{
			// Sequential format: all remaining data is one implicit page (page 1).
			final int seqLen = data.length - BLOCK_SIZE * 2;
			this.records = Collections.singletonList(new RawRecord(0, new byte[seqLen], 0, 0));
			if(seqLen > 0)
				System.arraycopy(data, BLOCK_SIZE * 2, this.records.get(0).raw, 0, seqLen);
			else
				this.records = Collections.emptyList();
			this.tailOffset = data.length;
		} else {
			parseVlir(data);
		}
	}

	/**
	 * Detect whether block 2 contains a valid VLIR sector.
	 *
	 * A GEOS VLIR sector is a 254-byte branch table. Used branches are
	 * [numBlocks, extra] pairs; unused slots are filled with the null marker
	 * [0x00, 0xFF]. Detection therefore requires BOTH at least one active
	 * branch entry AND a substantial run of null markers filling the rest of
	 * the table. Document content (sequential files) does not produce the
	 * null-fill pattern, so a lone branch-looking byte pair is not enough.
	 */
	private boolean detectVlir(final byte[] data)
	{
		int active = 0;
		int nulls = 0;
		final int entryLimit = Math.min(MAX_BRANCHES, BLOCK_SIZE / 2);
		for(int i = 0; i < entryLimit; i++)
		{
			final int off = VLIR_SECTOR_OFF + i * 2;
			if(off + 1 >= data.length)
				break;
			// VLIR entries are [numBlocks, extra] pairs:
			final int numBlk = data[off] & 0xFF;
			final int extra = data[off + 1] & 0xFF;

			if((numBlk == 0)&&(extra == 0)) // end-of-table marker
				break;
			if((numBlk == 0)&&(extra == VLIR_NULL_EXTRA)) // unused slot
				nulls++;
			else 
			if(numBlk > 0&&extra > 1) // active branch
				active++;
		}
		// A real VLIR sector has a small number of active entries and a long
		// run of null markers; sequential document data shows neither.
		return (active >= 1)&&(nulls >= 20);
	}

	private void parseVlir(final byte[] data)
	{
		final List<RawRecord> recs = new ArrayList<>();
		int offset = DATA_OFFSET;
		for(int branch = 0; branch < MAX_BRANCHES; branch++)
		{
			final int vlirOff = VLIR_SECTOR_OFF + branch * 2;
			if(vlirOff + 1 >= data.length) 
				break;

			final int numBlocks = data[vlirOff] & 0xFF;
			final int extra = data[vlirOff + 1] & 0xFF;

			// End of VLIR table: [0, 0x00]
			if(numBlocks == 0&&extra == 0) 
				break;

			// Null/unused branch: skip
			if(numBlocks == 0) 
				continue;

			int branchLen = numBlocks * BLOCK_SIZE;
			if(offset + branchLen > data.length)
				branchLen = Math.max(0, data.length - offset);
			if(branchLen <= 0) break;

			final byte[] raw = Arrays.copyOfRange(data, offset, offset + branchLen);
			offset += branchLen;
			recs.add(new RawRecord(branch, raw, numBlocks, extra));
		}
		this.records = recs;
		this.tailOffset = offset;
	}

	/**
	 * Get the page index from a string that may be "0", "h" for header, or a numeric record index.
	 */
	public static int parsePage(final String s)
	{
		if((s == null)||s.isEmpty()) 
			return -1;
		final String lower = s.toLowerCase();
		if("0".equals(lower)||"h".equals(lower)) 
			return 0;
		return Integer.parseInt(s);
	}

	/**
	 * Hex dump bytes to digits-only output with row addresses.
	 */
	private static String hexDump(final byte[] data, final int offset, final int count)
	{
		final int end = Math.min(offset + count, data.length);
		if(offset >= end) return "";
		final StringBuilder sb = new StringBuilder();
		int lineStart = offset;
		for(int i = offset; i < end; i += 16)
		{
			sb.append(String.format("%02x: ", i));
			for(int j = i; (j < i + 16)&&(j < end); j++)
			{
				if((j - lineStart) == 8) 
					sb.append(' ');
				sb.append(String.format("%02x", data[j] & 0xFF)).append(" ");
			}
			int padding = (16 * 3 + 1) - (end - i) * 3;
			for(int p = 0; (p < padding)&&((end - lineStart) - padding / 3 >= 0); p++) 
				sb.append(' ');
			sb.setLength(sb.length() - ((lineStart + (end - lineStart)) > end ? 1 : 0));
			sb.append('\n');
			lineStart = i + 16;
		}
		return sb.toString();
	}

	private static String hexDump(final byte[] data) 
	{
		return hexDump(data, 0, data.length);
	}

	/**
	 * Dump all records/pages in the file with sizes.
	 */
	public List<PageInfo> listPages()
	{
		final List<PageInfo> info = new ArrayList<>();
		if(headerBlock0 != null)
			info.add(new PageInfo(-1, "BLOCK0 (GEOS file header)", 254L, false, "HEADER"));
		if(headerBlock1 != null)
			info.add(new PageInfo(0, "BLOCK1 (GEOS info sector)", 254L, false, "HEADER"));
		if((!isSequential)&&(headerBlock0 != null))
		{
			// Block 2 is the VLIR sector
			info.add(new PageInfo(-2, "VLIR SECTOR", 254L, false, "HEADER"));
		}
		for(int i = 0; i < records.size(); i++)
		{
			final RawRecord rec = records.get(i);
			info.add(new PageInfo(rec.index, "record[" + rec.index + "]", rec.getLength(), rec.isNull, isSequential ? "SEQ" : "VLIR"));
		}
		return info;
	}

	public List<RawRecord> getRecords()
	{ 
		return records;
	}

	public boolean isSequentialFormat()
	{
		return isSequential;
	}

	/**
	 * The GEOS filename stored in the block-0 directory entry.
	 *
	 * @return the internal GEOS name, without padding
	 */
	public String getHeaderName()
	{
		final StringBuilder sb = new StringBuilder();
		if(headerBlock0 != null)
		{
			for(int i = 0; i < 16; i++)
			{
				final int c = headerBlock0[3 + i] & 0xFF;
				if((c == 0xA0)||(c == 0))
					break;
				sb.append((char)c);
			}
		}
		return sb.toString();
	}

	/**
	 * Set the GEOS filename in the in-memory block-0 directory entry, padding
	 * with $A0 and truncating at 16 characters.  Does not write the file.
	 *
	 * @param name the new internal GEOS name
	 */
	public void setHeaderName(final String name)
	{
		if((headerBlock0 == null)||(headerBlock0.length < 19))
			return;
		final byte[] nb = headerBlock0.clone();
		for(int i = 0; i < 16; i++)
			nb[3 + i] = (byte)0xA0;
		final byte[] raw = name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		System.arraycopy(raw, 0, nb, 3, Math.min(16, raw.length));
		headerBlock0 = nb;
	}

	/**
	 * RENAME: set the GEOS filename in the directory entry and rewrite the
	 * file, preserving every record and all other header bytes.
	 *
	 * @param name the new internal GEOS name
	 * @throws IOException on write errors, or if there is no source file
	 */
	public void setName(final String name) throws IOException
	{
		if(sourceFile == null)
			throw new IOException("Cannot rename: no source file");
		setHeaderName(name);
		if(isSequential)
			writeSequencedFile(records.isEmpty() ? new byte[0] : records.get(0).raw);
		else
			writeVlirFile(records);
	}

	/**
	 * Read a raw record by 1-based positional page. Page 0 / "h" returns the
	 * GEOS header block (block 1). For sequential format, page >= 1 returns
	 * the single implicit record.
	 */
	public RawRecord readRecord(final int idx)
	{
		// Page 0 / "h": return header block 1 (GEOS directory entry)
		if(idx <= 0||"h".equalsIgnoreCase(String.valueOf(idx)))
			return new RawRecord(0, headerBlock1.clone(), 1, BLOCK_SIZE);
		if(isSequential) 
		{
			if(records.isEmpty())
				return null;
			return records.get(0);
		}
		else
		{
			// Pages are positional: page N = the Nth document record (index N-1).
			final int pos = idx - 1;
			if(pos < 0||pos >= records.size())
				return null;
			return records.get(pos);
		}
	}

	/**
	 * REWRITE: replace bytes in a page/record starting at offset.
	 * The record is expanded or contracted to accommodate the new data, and
	 * the VLIR sector is updated accordingly.
	 */
	public boolean rewriteRecord(final int pageNum, final int offset, final byte[] replacement) throws IOException
	{
		if((sourceFile == null)&&(this.sourceFile == null))
			throw new IOException("Cannot rewrite: no source file");

		final RawRecord rec;
		if(isSequential)
		{
			if(records.isEmpty())
				return false;
			rec = records.get(0);
		}
		else
		{
			if((pageNum < 1)||(pageNum > records.size()))
				throw new IndexOutOfBoundsException("Page " + pageNum + " out of range (1-" + records.size() + ")");
			rec = records.get(pageNum - 1);
		}

		final byte[] currentRaw = rec.clone();
		if((offset < 0)||(offset > currentRaw.length))
			throw new IOException("Offset " + offset + " out of range for page " + pageNum);

		final int delta = replacement.length;
		if(offset + delta <= currentRaw.length)
		{
			// Replace in-place within existing buffer
			final byte[] newRaw = Arrays.copyOf(currentRaw, Math.max(currentRaw.length, currentRaw.length - delta + delta));
			System.arraycopy(replacement, 0, newRaw, offset, replacement.length);
			if(isSequential)
				return writeSequencedFile(newRaw);
			else
			{
				final List<RawRecord> updated = new ArrayList<>(records);
				updated.set(pageNum - 1, RawRecord.fromRawLength(pageNum - 1, newRaw));
				return writeVlirFile(updated);
			}
		}
		else
		{
			// Grow the record: pad up to offset+delta and replace
			final byte[] padded = Arrays.copyOf(currentRaw, offset + delta);
			System.arraycopy(replacement, 0, padded, offset, replacement.length);
			if(isSequential)
				return writeSequencedFile(padded);
			else
			{
				final List<RawRecord> updated = new ArrayList<>(records);
				updated.set(pageNum - 1, RawRecord.fromRawLength(pageNum - 1, padded));
				return writeVlirFile(updated);
			}
		}
	}

	private boolean writeSequencedFile(final byte[] newPageData) throws IOException
	{
		final int totalLen = BLOCK_SIZE * 2 + newPageData.length;
		final byte[] newFile = new byte[totalLen];
		System.arraycopy(headerBlock0, 0, newFile, 0, BLOCK_SIZE);
		System.arraycopy(headerBlock1, 0, newFile, BLOCK_SIZE, BLOCK_SIZE);
		System.arraycopy(newPageData, 0, newFile, BLOCK_SIZE * 2, newPageData.length);

		writeNewFile(newFile);
		this.records = Collections.singletonList(new RawRecord(0, newPageData.clone(), 0, 0));
		return true;
	}

	protected void writePages(final List<RawRecord> records) throws IOException 
	{
		writeVlirFile(records);
	}

	private boolean writeVlirFile(final List<RawRecord> updatedPages) throws IOException
	{
		// Build new VLIR sector
		final byte[] newVlir = new byte[BLOCK_SIZE];
		for(int b = 0; b < MAX_BRANCHES; b++)
		{
			newVlir[b * 2] = 0;
			newVlir[(b * 2) + 1] = (byte) VLIR_NULL_EXTRA;
		}

		final ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
		final boolean lastInFile = tailOffset() >= rawFile.length;

		int branchIdx = 0;
		for(int i = 0; i < updatedPages.size(); i++)
		{
			final RawRecord rec = updatedPages.get(i);
			final int numBlocks = Math.max(1, (rec.getLength() + BLOCK_SIZE - 1) / BLOCK_SIZE);
			final int rem = rec.getLength() % BLOCK_SIZE;
			final int extra = rem == 0 ? 1 : rem + 1;

			final byte[] stored = ((i == updatedPages.size() - 1)&&lastInFile)
				? Arrays.copyOf(rec.raw, rec.getLength())
				: Arrays.copyOf(rec.raw, numBlocks * BLOCK_SIZE);

			newVlir[branchIdx * 2] = (byte) numBlocks;
			newVlir[(branchIdx * 2) + 1] = (byte) extra;
			dataOut.write(stored, 0, stored.length);
			branchIdx++;
		}

		if(tailOffset() < rawFile.length)
			dataOut.write(rawFile, tailOffset(), rawFile.length - tailOffset());

		final ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
		fileOut.write(headerBlock0, 0, BLOCK_SIZE);
		fileOut.write(headerBlock1, 0, BLOCK_SIZE);
		fileOut.write(newVlir, 0, newVlir.length);
		fileOut.write(dataOut.toByteArray(), 0, dataOut.size());

		writeNewFile(fileOut.toByteArray());
		this.records = updatedPages;
		return true;
	}

	private int tailOffset()
	{ 
		return tailOffset;
	}

	protected void writeNewFile(final byte[] newFile) throws IOException
	{
		if(this.sourceFile == null)
			throw new IOException("No source file to write");
		final File temp = new File(this.sourceFile.getParentFile(), this.sourceFile.getName() + ".geomod_tmp");
		try(final FileOutputStream fout = new FileOutputStream(temp))
		{
			fout.write(newFile);
		}
		if((temp.length() != newFile.length)||!this.sourceFile.delete())
		{
			temp.delete();
			throw new IOException("Error replacing " + this.sourceFile.getName());
		}
		if(!temp.renameTo(this.sourceFile))
		{
			throw new IOException("Error renaming " + temp.getName() + " to " + sourceFile.getName());
		}
		this.rawFile = newFile;
		// Re-parse to update state
		parse(newFile);
	}

	/**
	 * INSERTPAGE: insert a blank page/record at 1-based position pageNum.
	 * VLIR files only. The new record gets the next free branch index.
	 */
	public void insertPage(final int pageNum) throws IOException
	{
		if(isSequential||records.isEmpty())
			throw new IOException("INSERTPAGE not supported for sequential format");
		if((pageNum < 1)||(pageNum > records.size() + 1))
			throw new IndexOutOfBoundsException("Page " + pageNum + " out of range (1-" + (records.size() + 1) + ")");

		int nextBranch = 0;
		for(final RawRecord rec : records)
		{
			if(rec.index >= nextBranch)
				nextBranch = rec.index + 1;
		}

		final byte[] blank = new byte[BLOCK_SIZE]; // minimum one block
		final List<RawRecord> updated = new ArrayList<>(records.size() + 1);
		int pos = 0;
		boolean added = false;
		for(final RawRecord rec : records)
		{
			if(pos == pageNum - 1)
			{
				updated.add(RawRecord.fromRawLength(nextBranch, blank.clone()));
				added = true;
			}
			updated.add(rec);
			pos++;
		}
		if(!added) 
			updated.add(RawRecord.fromRawLength(nextBranch, blank.clone()));

		writeVlirFile(updated);
	}

	/**
	 * DELETEPAGE: delete the page at 1-based position pageNum. VLIR files only.
	 */
	public void deletePage(final int pageNum) throws IOException
	{
		if(isSequential)
			throw new IOException("DELETEPAGE not supported for sequential format");
		if((pageNum < 1)||(pageNum > records.size()))
			throw new IndexOutOfBoundsException("Page " + pageNum + " out of range (1-" + records.size() + ")");
		final List<RawRecord> updated = new ArrayList<>(records);
		updated.remove(pageNum - 1);
		writeVlirFile(updated);
	}

	/**
	 * COPYPAGE: duplicate the page at 1-based position srcPage, inserting the
	 * copy so it lands at 1-based position dstPage (1..size+1). VLIR files only.
	 * The original source page is left untouched.
	 */
	public void copyPage(final int srcPage, final int dstPage) throws IOException
	{
		if(isSequential) 
			throw new IOException("COPYPAGE not supported for sequential format");
		if((srcPage < 1)||(srcPage > records.size()))
			throw new IndexOutOfBoundsException("Source page " + srcPage + " out of range (1-" + records.size() + ")");
		if((dstPage < 1)||(dstPage > records.size() + 1))
			throw new IndexOutOfBoundsException("Destination page " + dstPage + " out of range (1-" + (records.size() + 1) + ")");

		int nextBranch = 0;
		for(final RawRecord rec : records)
		{
			if(rec.index >= nextBranch) 
				nextBranch = rec.index + 1;
		}

		final RawRecord source = records.get(srcPage - 1);
		final RawRecord duplicate = new RawRecord(nextBranch, source.raw.clone(), source.numBlocks, source.extra);
		final List<RawRecord> updated = new ArrayList<>(records.size() + 1);
		int pos = 0;
		boolean added = false;
		for(final RawRecord rec : records)
		{
			if(pos == dstPage - 1)
			{
				updated.add(duplicate);
				added = true;
			}
			updated.add(rec);
			pos++;
		}
		if(!added) 
			updated.add(duplicate);
		writeVlirFile(updated);
	}

	/**
	 * MOVEPAGE: move page at 1-based position to another position. VLIR files only.
	 */
	public void movePage(final int srcPage, final int dstPage) throws IOException
	{
		if(isSequential) 
			throw new IOException("MOVEPAGE not supported for sequential format");
		if((srcPage < 1)||(srcPage > records.size()))
			throw new IndexOutOfBoundsException("Source page " + srcPage + " out of range (1-" + records.size() + ")");
		if((dstPage < 1)||(dstPage > records.size()))
			throw new IndexOutOfBoundsException("Destination page " + dstPage + " out of range (1-" + records.size() + ")");

		final RawRecord source = records.get(srcPage - 1);
		final List<RawRecord> updated = new ArrayList<>(records);
		updated.remove(srcPage - 1);
		updated.add(dstPage - 1, source);
		writeVlirFile(updated);
	}

	public static void usage()
	{
		System.out.println("GeoMod v1.0 - Binary page/record editor for GEOS .CVT files");
		System.out.println("");
		System.out.println("USAGE:");
		System.out.println("  GeoMod READ [file.cvt] [page/record] [offset] [count]");
		System.out.println("    - Hex dump of page or region (digits only)");
		System.out.println("    page=0 or 'h' = GEOS header block, omit page = implicit page 1");
		System.out.println("  GeoMod LISTPAGES [file.cvt]");
		System.out.println("    - List all records/pagination with sizes");
		System.out.println("  GeoMod INSERTPAGE [file.cvt] [page]");
		System.out.println("    - Insert blank record at position (VLIR files only)");
		System.out.println("  GeoMod DELETEPAGE [file.cvt] <page/record>");
		System.out.println("    - Delete record from file");
		System.out.println("  GeoMod REWRITE [file.cvt] <page> <offset> <hexbytes>");
		System.out.println("    - Replace bytes in page starting at offset");
		System.out.println("    hexbytes: space-separated or contiguous hex digits, e.g. 'ff 0a' or 'ffa2'");
		System.out.println("  GeoMod COPYPAGE [file.cvt] <srcpage> <dstpage>");
		System.out.println("    - Copy page to branch position (VLIR files only)");
		System.out.println("  GeoMod MOVEPAGE [file.cvt] <srcpage> <dstpage>");
		System.out.println("    - Move page to branch position (VLIR files only)");
		System.out.println("  GeoMod RENAME [file.cvt] <newname>");
		System.out.println("    - Set the GEOS filename in the directory entry");
		System.out.println("");
		System.out.println("  file.cvt   path to a GEOS .CVT document");
		System.out.println("  page       1-based page number (0 = GEOS header, 'h' also = header)");
		return;
	}

	public static void main(final String[] args)
	{
		if(args.length < 2)
		{ 
			usage();
			System.exit(2);
			return;
		}

		try 
		{
			if("READ".equalsIgnoreCase(args[0]))
			{
				final GeoMod gm = GeoMod.fromFile(args[1]);
				if(gm.records.isEmpty()&&(!gm.isSequential))
				{
					System.out.println("No records found in " + args[1]);
					return;
				}

				String pageStr = null;
				int offset = 0, count = -1;
				if(args.length >= 3)
					pageStr = args[2];
				if((args.length >= 4)&&(pageStr != null))
				{
					try 
					{
						offset = Integer.parseInt(args[3]); 
					} 
					catch(final NumberFormatException e)
					{
						System.err.println("Error: invalid offset '" + args[3] + "'");
						return;
					}
				}
				if((args.length >= 5)&&(pageStr != null))
				{
					try
					{
						count = Integer.parseInt(args[4]);
					}
					catch(final NumberFormatException e)
					{
						System.err.println("Error: invalid count '" + args[4] + "'");
						return;
					}
				}

				if("0".equalsIgnoreCase(pageStr)||"h".equalsIgnoreCase(pageStr)) 
				{
					// Dump header block 1 (GEOS directory entry)
					System.out.println("=== GEOS Header Block (page " + pageStr + ") ===");
					if(gm.headerBlock1 != null)
						System.out.print(hexDump(gm.headerBlock1));
					return;
				}

				if(gm.isSequential) 
				{
					if(!gm.records.isEmpty())
					{
						System.out.println("=== SEQ Page 1 ===");
						final byte[] raw = gm.records.get(0).raw;
						if(count < 0)
							count = raw.length - offset;
						System.out.print(hexDump(raw, offset, count));
					}
					else
						System.out.println("No data in sequential file.");
				}
				else
				{
					int page = 1;
					if((pageStr != null)&&(!"".equals(pageStr)))
					{
						try
						{
							page = parsePage(pageStr);
						}
						catch(final NumberFormatException e)
						{
							System.err.println("Error: invalid page '" + pageStr + "'");
							return;
						}
					}
					final RawRecord rec = gm.readRecord(page);
					if(rec != null)
					{
						final int pos = page - 1;
						System.out.println("=== page " + page + " (record[" + pos + "], branch " + rec.index + ") ===");
						if(count < 0)
							count = rec.raw.length - offset;
						System.out.print(hexDump(rec.raw, offset, count));
					}
					else
						System.err.println("Error: No record at page " + page);
				}
			}
			else
			if("LISTPAGES".equalsIgnoreCase(args[0])) 
			{
				final GeoMod gm = GeoMod.fromFile(args[1]);
				System.out.println("=== " + args[1] + " (" + gm.getHeaderName() + ") ===");
				for(final PageInfo pi : gm.listPages())
					System.out.printf("  %-30s size=%-8d type=%s %n", pi.name, pi.size, pi.type);
			} 
			else 
			if("INSERTPAGE".equalsIgnoreCase(args[0])) 
			{
				if(args.length < 3)
				{
					usage();
					return;
				}
				final int pageNum;
				try
				{ 
					pageNum = parsePage(args[2]);
				}
				catch(final NumberFormatException e) 
				{
					System.err.println("Error: invalid page '" + args[2] + "'");
					return;
				}
				if(pageNum < 1)
				{
					System.err.println("Error: page must be >= 1"); 
					return;
				}
				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.insertPage(pageNum);
				System.out.println("Inserted blank page " + pageNum + ".");
			}
			else
			if("DELETEPAGE".equalsIgnoreCase(args[0]))
			{
				if(args.length < 3)
				{
					usage();
					return;
				}
				final int pageNum;
				try
				{
					pageNum = parsePage(args[2]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid page '" + args[2] + "'");
					return;
				}
				if(pageNum <= 0)
				{
					System.err.println("Error: cannot delete header block (page " + pageNum + ")");
					return;
				}
				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.deletePage(pageNum);
				System.out.println("Deleted page " + pageNum + ".");
			}
			else
			if("REWRITE".equalsIgnoreCase(args[0]))
			{
				if(args.length < 5)
				{ 
					usage();
					return; 
				}
				final int pageNum, offset;
				try
				{
					pageNum = parsePage(args[2]);
					offset = Integer.parseInt(args[3]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid page or offset");
					return;
				}

				String hexStr = args[4];
				for(int i = 5; i < args.length; i++)
					hexStr += " " + args[i];

				final byte[] replacement = hexStringToBytes(hexStr);
				if(replacement == null)
				{
					System.err.println("Error: invalid hex string '" + hexStr + "'");
					return;
				}

				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.rewriteRecord(pageNum, offset, replacement);
				System.out.println("Rewrote " + replacement.length + " byte(s) at page " + pageNum + ", offset 0x" + Integer.toHexString(offset));
			}
			else
			if("COPYPAGE".equalsIgnoreCase(args[0]))
			{
				if(args.length < 4)
				{
					usage();
					return;
				}
				final int src, dst;
				try
				{
					src = parsePage(args[2]);
					dst = parsePage(args[3]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid page number");
					return;
				}
				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.copyPage(src, dst);
				System.out.println("Copied page " + src + " to page " + dst + ".");
			}
			else
			if("MOVEPAGE".equalsIgnoreCase(args[0]))
			{
				if(args.length < 4)
				{
					usage();
					return;
				}
				final int src, dst;
				try
				{
					src = parsePage(args[2]);
					dst = parsePage(args[3]);
				}
				catch(final NumberFormatException e)
				{
					System.err.println("Error: invalid page number");
					return;
				}
				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.movePage(src, dst);
				System.out.println("Moved page " + src + " to page " + dst + ".");
			}
			else
			if("RENAME".equalsIgnoreCase(args[0])||"SETNAME".equalsIgnoreCase(args[0]))
			{
				if(args.length < 3)
				{
					usage();
					return;
				}
				final GeoMod gm = GeoMod.fromFile(args[1]);
				gm.setName(args[2]);
				System.out.println("Renamed " + args[1] + " to " + gm.getHeaderName() + ".");
			}
			else
			{
				usage();
				System.exit(2);
			}
		}
		catch(final IOException e)
		{
			System.err.println("Error: " + e.getMessage());
			System.exit(2);
		}
		catch(final IndexOutOfBoundsException e)
		{
			System.err.println("Error: " + e.getMessage());
			System.exit(3);
		}
	}

	private static byte[] hexStringToBytes(final String hexStr)
	{
		final StringTokenizer st = new StringTokenizer(hexStr);
		if(!st.hasMoreTokens()) 
			return new byte[0];

		int count;
		String token = st.nextToken();
		// Single char or non-hex — treat as space-separated pairs
		if((token.length() % 2 == 1)||(!token.matches("^[0-9a-fA-F]+$")))
			count = 1 + st.countTokens();
		else
		if(token.length() > 2)
			count = token.length() / 2;
		else
			count = 1 + st.countTokens();
		if(count == 0)
			return new byte[0];
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try
		{
			// First: try space-separated pairs
			try
			{
				token = token.replaceAll("([0-9a-fA-F]{2})", "$1 ").trim();
				for(final String part : token.split("\\s+"))
				{
					if(!part.isEmpty()) 
						baos.write(Integer.parseInt(part, 16));
				}
				while(st.hasMoreTokens())
				{
					final String next = st.nextToken().replaceAll("([0-9a-fA-F]{2})", "$1 ").trim();
					for(final String part : next.split("\\s+"))
					{
						if(!part.isEmpty()) 
							baos.write(Integer.parseInt(part, 16));
					}
				}
				return baos.toByteArray();
			}
			catch(final Exception e)
			{
				// Fall back to treating as single contiguous hex string
				baos.reset();
				for(int i = 0; i < token.length(); i += 2)
					baos.write((byte) Integer.parseInt(token.substring(i, i + 2), 16));
				return baos.toByteArray();
			}
		}
		catch(final Exception e)
		{
			return null;  // invalid hex string
		}
	}
}
