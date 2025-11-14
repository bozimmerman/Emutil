package com.planet_ink.emutil.archives;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.D64Base;

public class T64
{
	public static List<FileInfo> getTapeFiles(final byte[][][] tsmap, final int fileSize, final String imgName, final BitSet parseFlags)
	{
		final List<FileInfo> finalData=new Vector<FileInfo>();
		final byte[] data = tsmap[0][0];
		final String marker = new String(data,0,32);
		if(!marker.startsWith("C64"))
		{
			if(!parseFlags.get(D64Base.PF_NOERRORS))
				D64Base.errMsg(imgName+": tape header error.");
			return finalData;
		}
		//final int tapeVersionNumber = (data[32] & 0xff) + (data[33] & 0xff);
		final int numEntries = (data[34] & 0xff) + (256 * (data[35] & 0xff));
		//final int usedEntries = (data[36] & 0xff)+ (256 * (data[37] & 0xff)); // might be gaps, so dont use this!
		// we skip the tape/disk name. :(

		// first must do a pre-scan, because some T64s are Wrong.
		final TreeSet<Integer> fileStarts = new TreeSet<Integer>();
		for(int start = 64; start < (64+(32*numEntries)) && (start < data.length); start+=32)
		{
			if(data[start]!=1)
				continue;
			final int startAddress = (data[start+2] & 0xff) + (256*(data[start+3] & 0xff));
			fileStarts.add(Integer.valueOf(startAddress));
		}

		for(int start = 64; start < (64+(32*numEntries)) && (start < data.length); start+=32)
		{
			if(data[start]!=1)
				continue;
			final int startAddress = (data[start+2] & 0xff) + (256*(data[start+3] & 0xff));
			int endAddress = (data[start+4]& 0xff) + (256*(data[start+5]& 0xff));
			if(endAddress == 50118) // this means it's wrong.
			{
				for(final Iterator<Integer> i = fileStarts.iterator();i.hasNext();)
				{
					final Integer startI = i.next();
					if(startI.intValue() == startAddress)
					{
						if(i.hasNext())
							endAddress = i.next().intValue()-1;
						else
							endAddress = fileSize-1;
						break;
					}
				}
			}
			final int dataOffset = (data[start+8] & 0xff)
									+ (256*(data[start+9] & 0xff))
									+ (65536 * (data[start+10] & 0xff)); // nothing higher is Good.
			final int fileLength = endAddress-startAddress+1;
			final FileInfo f = new FileInfo();
			finalData.add(f);
			f.header = Arrays.copyOfRange(data, start, start+32);
			final StringBuffer file=new StringBuffer("");
			int fn=start+32-1;
			for(;fn>=start+16;fn--)
			{
				if((data[fn]!=-96)
				&&(data[fn]!=0)
				&&(data[fn]!=32))
					break;
			}
			final byte[] rawFilename = new byte[fn-start-16+1];
			for(int x=start+16;x<=fn;x++)
			{
				file.append((char)data[x]);
				rawFilename[x-start-16] = data[x];
			}
			for(int ii=0;ii<file.length();ii++)
				file.setCharAt(ii, D64Base.convertToPetscii((byte)file.charAt(ii))); // this makes no sense to me
			f.filePath="/" + file.toString();
			f.fileName=file.toString();
			f.rawFileName = rawFilename;
			f.size=fileLength;
			{
				final byte[] bytes = D64Base.numToBytes(start);
				f.dirLoc=new short[] {(short)(bytes[0]&0xff),(short)(bytes[1]&0xff),(short)(bytes[2]&0xff)};
			}
			f.feblocks = (int)Math.round(Math.floor(f.size / 254));
			if(data[start+1]!=0) // this is mega lame -- what a messed up format
				f.fileType = FileType.PRG;
			else
				f.fileType = FileType.SEQ;
			final int fakeStart = (f.fileType == FileType.PRG)?dataOffset-2:dataOffset;
			if(fakeStart + f.size + 1 > fileSize)
			{
				if(!parseFlags.get(D64Base.PF_NOERRORS))
					D64Base.errMsg(imgName+": has bad offsets for file "+f.filePath+", trimming it.");
				f.data = Arrays.copyOfRange(data, fakeStart, fileSize);
			}
			else
				f.data = Arrays.copyOfRange(data, fakeStart, fakeStart+f.size+1);
			if(f.fileType == FileType.PRG)
			{
				f.data[0] = data[start+2];
				f.data[1] = data[start+3];
			}
		}
		return finalData;
	}

}
