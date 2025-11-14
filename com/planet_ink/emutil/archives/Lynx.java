package com.planet_ink.emutil.archives;
import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.D64Base;

public class Lynx
{

	private static String getNextLYNXLineFromInputStream(final InputStream in, final int[] bytesRead, final OutputStream bout) throws IOException
	{
		final StringBuilder line=new StringBuilder("");
		while(in.available()>0)
		{
			int b=in.read();
			if(b<0)
				throw new IOException("Unexpected EOF");
			if(bytesRead != null)
				bytesRead[0]++;
			if(b==13)
			{
				if(line.length()>0)
					break;
			}
			if(bout != null)
				bout.write(b);
			if(b == 160)
				continue;
			b = D64Base.convertToAscii(b);
			if(b != 0)
				line.append((char)b);
		}
		return line.toString();
	}

	public static List<FileInfo> getLNXDeepContents(final byte[] data) throws IOException
	{
		final List<FileInfo> list = new ArrayList<FileInfo>();
		final InputStream in = new ByteArrayInputStream(data);
		final int[] bytesSoFar=new int[1];
		int zeroes=0;
		while(in.available()>0)
		{
			final int b = in.read();
			if(b<0)
				break;
			bytesSoFar[0]++;
			if(b == 0)
			{
				zeroes++;
				if(zeroes==3)
					break;
			}
			else
				zeroes=0;
		}
		if(zeroes != 3)
			throw new IOException("Illegal LNX format: missing 0 0 0");
		final String sigLine = getNextLYNXLineFromInputStream(in, bytesSoFar, null).toUpperCase().trim();
		int headerSize = 0;
		final String[] splitSig = sigLine.split(" ");
		final String sz = splitSig[0].trim();
		if((sz.length()>0)
		&&(Character.isDigit(sz.charAt(0)))
		&&(sz.length()<20))
			headerSize = Integer.parseInt(sz);
		if((sigLine.length()==0)
		||(headerSize <= 0)
		||(sigLine.indexOf("LYNX")<0))
			throw new IOException("Illegal signature: "+sigLine);
		final int numEntries;
		if(D64Base.isInteger(splitSig[splitSig.length-1])
		&&(Integer.parseInt(splitSig[splitSig.length-1])<1000))
		{
			numEntries = Integer.parseInt(splitSig[splitSig.length-1]);
		}
		else
		{
			final String numEntryLine = getNextLYNXLineFromInputStream(in, bytesSoFar, null).toUpperCase().trim();
			if((numEntryLine.length()==0)
			||(!Character.isDigit(numEntryLine.charAt(0))))
				throw new IOException("Illegal numEntries: "+numEntryLine);
			numEntries = Integer.parseInt(numEntryLine);
		}
		final byte[] rawDirBlock = new byte[(254 - bytesSoFar[0]) + ((headerSize-1) * 254)];
		int bytesRead = 0;
		while((in.available()>0) && (bytesRead < rawDirBlock.length))
		{
			final int justRead = in.read(rawDirBlock, bytesRead, rawDirBlock.length-bytesRead);
			if(justRead < 0)
				break;
			if(justRead > 0)
				bytesRead += justRead;
		}
		if(bytesRead < rawDirBlock.length)
			throw new IOException("Incomplete Directory Block");
		final ByteArrayInputStream bin=new ByteArrayInputStream(rawDirBlock);
		for(int i=0;i<numEntries;i++)
		{
			final ByteArrayOutputStream fout = new ByteArrayOutputStream();
			final String fileName =  getNextLYNXLineFromInputStream(bin, null, fout); // don't trim, cuz spaces are valid.
			final String numBlockSz= getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			final String typChar =   getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			String lastBlockSz=getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			if((lastBlockSz.length()==0)||(!Character.isDigit(lastBlockSz.charAt(0))))
				lastBlockSz="0";

			if((fileName.length()==0)
			||(numBlockSz.length()==0)||(!Character.isDigit(numBlockSz.charAt(0)))
			||(typChar.length()==0)||(typChar.length()>3)
			||(lastBlockSz.length()==0)||(!Character.isDigit(lastBlockSz.charAt(0))))
				throw new IOException("Bad directory entry "+(i+1)+": "+fileName+"."+typChar+": "+numBlockSz+"("+lastBlockSz+")");
			final FileInfo file = new FileInfo();
			file.fileName = fileName;
			file.filePath = fileName;
			file.rawFileName = fout.toByteArray();
			file.fileType = FileType.fileType(typChar);
			file.size = ((Integer.valueOf(numBlockSz).intValue()-1) * 254) + Integer.valueOf(lastBlockSz).intValue()-1;
			file.feblocks = Integer.valueOf(numBlockSz).intValue();
			list.add(file);
		}
		for(final FileInfo f : list)
		{
			int fbytesRead = 0;
			int numBlocks = (int)Math.round(Math.floor(f.size / 254.0));
			if((f.size % 254) > 0)
				numBlocks++;
			final int allBlocksSize = numBlocks * 254;
			final byte[] fileSubBytes = new byte[allBlocksSize];
			while((in.available()>0) && (fbytesRead < allBlocksSize))
			{
				final int justRead = in.read(fileSubBytes, fbytesRead, allBlocksSize-fbytesRead);
				if(justRead < 0)
					break;
				if(justRead > 0)
					fbytesRead += justRead;
			}
			if(fbytesRead < allBlocksSize)
			{
				if((list.get(list.size()-1)!=f)
				||(fbytesRead < f.size-1024))
				{
					System.err.println("Incomplete data for "+f.fileName+" in LYNX file.");
					if(f == list.get(0))
						throw new IOException("Incomplete data for "+f.fileName);
					return list;
				}
			}
			f.data = Arrays.copyOf(fileSubBytes, f.size);
		}
		return list;
	}

	public static int writeLNX(final List<FileInfo> info, final OutputStream out) throws IOException
	{
		final int[] lynxHeader = new int[] {
			0x01, 0x08, 0x5B, 0x08, 0x0A, 0x00, 0x97, 0x35, /* .......5 */
			0x33, 0x32, 0x38, 0x30, 0x2C, 0x30, 0x3A, 0x97, /* 3280,0:. */
			0x35, 0x33, 0x32, 0x38, 0x31, 0x2C, 0x30, 0x3A, /* 53281,0: */
			0x97, 0x36, 0x34, 0x36, 0x2C, 0xC2, 0x28, 0x31, /* .646,.(1 */
			0x36, 0x32, 0x29, 0x3A, 0x99, 0x22, 0x93, 0x11, /* 62):.".. */
			0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x22, /* ......." */
			0x3A, 0x99, 0x22, 0x20, 0x20, 0x20, 0x20, 0x20, /* :."      */
			0x55, 0x53, 0x45, 0x20, 0x4C, 0x59, 0x4E, 0x58, /* USE LYNX */
			0x20, 0x54, 0x4F, 0x20, 0x44, 0x49, 0x53, 0x53, /*  TO DISS */
			0x4F, 0x4C, 0x56, 0x45, 0x20, 0x54, 0x48, 0x49, /* OLVE THI */
			0x53, 0x20, 0x46, 0x49, 0x4C, 0x45, 0x22, 0x3A, /* S FILE": */
			0x89, 0x31, 0x30, 0x00, 0x00, 0x00, 0x0D
		};
		final int[] lynxSig = new int[] {
			0x2A, 0x4C, 0x59, 0x4E, 0x58, 0x20, 0x41, 0x52, /* *LYNX AR */
			0x43, 0x48, 0x49, 0x56, 0x45, 0x20, 0x42, 0x59, /* CHIVE BY */
			0x20, 0x50, 0x4F, 0x57, 0x45, 0x52, 0x32, 0x30, /*  POWER20 */
			0x0D
		};
		final List<FileInfo> finfo = new ArrayList<FileInfo>(info.size());
		for(final FileInfo F : info)
		{
			if((F.rawFileName.length>0)
			&&(F.data!=null)
			&&(F.data.length>0))
				finfo.add(F);
		}
		if(finfo.size()==0)
			throw new IOException("No legitimate files found.");
		int bytesWritten = 0;
		final ByteArrayOutputStream unpaddedHeader = new ByteArrayOutputStream();
		{
			int headerSize = 0;
			final List<byte[]> dirEntries = new ArrayList<byte[]>();
			for(final FileInfo F : finfo)
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				for(int i=0;i<16;i++)
				{
					if(i<F.rawFileName.length)
						bout.write(F.rawFileName[i]);
					else
						bout.write((byte)160);
				}
				bout.write((byte)13);

				final int numBlocks = (int)Math.round(Math.ceil(F.data.length / 254.0));
				final int extraBytes = F.data.length % 254;
				bout.write((byte)32);
				final String numBlksStr = (""+numBlocks);
				bout.write(numBlksStr.getBytes("US-ASCII"));
				bout.write((byte)13);

				bout.write((byte)(F.fileType.name().toUpperCase().charAt(0))); // lowercase petscii char?
				bout.write((byte)13);

				bout.write((byte)32);
				final String exBytesStr = (""+(extraBytes+1)); //TODO: WHY THIS?!?!?!????!!!
				bout.write(exBytesStr.getBytes("US-ASCII"));
				bout.write((byte)32);
				bout.write((byte)13);

				dirEntries.add(bout.toByteArray());
				headerSize += bout.toByteArray().length;
			}
			final byte[] numEntries;
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write((byte)32);
				final String numEntStr = (""+dirEntries.size());
				bout.write(numEntStr.getBytes("US-ASCII"));
				for(int i=numEntStr.length();i<3;i++)
					bout.write(32);
				bout.write((byte)13);
				numEntries = bout.toByteArray();
			}
			headerSize += numEntries.length;
			headerSize += lynxHeader.length + 4 + lynxSig.length;
			final int numBlocksNeeded = (int)Math.round(Math.ceil((double)headerSize / (double)254));
			final byte[] numHeaderBlocks;
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write((byte)32);
				final String numBlksStr = ""+numBlocksNeeded;
				bout.write(numBlksStr.getBytes("US-ASCII"));
				for(int i=numBlksStr.length();i<3;i++)
					bout.write((byte)32);
				numHeaderBlocks = bout.toByteArray();
			}

			//*** now build the final header ***
			for(int i=0;i<lynxHeader.length;i++)
				unpaddedHeader.write((byte)(lynxHeader[i] & 0xff));
			unpaddedHeader.write(numHeaderBlocks);
			for(int i=0;i<lynxSig.length;i++)
				unpaddedHeader.write((byte)(lynxSig[i] & 0xff));
			unpaddedHeader.write(numEntries);
			for(final byte[] dirEntry : dirEntries)
				unpaddedHeader.write(dirEntry);
		}
		//** write the header
		final int numPaddingBytes = 254 - (unpaddedHeader.size() % 254);
		bytesWritten += unpaddedHeader.size() + numPaddingBytes;
		out.write(unpaddedHeader.toByteArray());
		unpaddedHeader.reset();
		for(int i=0;i<numPaddingBytes;i++)
			out.write(0);
		for(int f=0;f<finfo.size();f++)
		{
			final FileInfo F = finfo.get(f);
			final int extraBytes = 254 - (F.data.length % 254);
			out.write(F.data);
			bytesWritten += F.data.length;
			if(f<finfo.size()-1)
			{
				bytesWritten += extraBytes;
				for(int i=0;i<extraBytes;i++)
					out.write(0);
			}
		}
		out.flush();
		return bytesWritten;
	}

}
