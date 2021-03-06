package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

/*
Copyright 2017-2017 Bo Zimmerman

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
public class D64FileMatcher extends D64Mod
{
	public enum COMP_FLAG {
		VERBOSE,
		RECURSE,
		CACHE
	};
	public enum FILE_FORMAT {
		PETSCII,
		ASCII,
		HEX;
	};

	private static class FMCache
	{
		List<FileInfo>	fileData1	= null;
	}

	public static List<FileInfo> getLNXDeepContents(final File F) throws IOException
	{
		FileInputStream fin=new FileInputStream(F);
		try
		{
			long fileLen = F.length();
			return getLNXDeepContents(fin);
		}
		finally
		{
			fin.close();
		}
	}
	
	public static String getNextLYNXLineFromInputStream(final InputStream in, int[] bytesRead) throws IOException
	{
		StringBuilder line=new StringBuilder("");
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
			if(b == 160)
				continue;
			b = D64Base.convertToAscii(b);
			if(b != 0)
				line.append((char)b);
		}
		return line.toString();
	}
	
	public static List<FileInfo> getLNXDeepContents(final InputStream in) throws IOException
	{
		final List<FileInfo> list = new ArrayList<FileInfo>();
		int[] bytesSoFar=new int[1];
		int zeroes=0;
		while(in.available()>0)
		{
			int b = in.read();
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
		final String sigLine = getNextLYNXLineFromInputStream(in, bytesSoFar).toUpperCase().trim();
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
		final String numEntryLine = getNextLYNXLineFromInputStream(in, bytesSoFar).toUpperCase().trim();
		if((numEntryLine.length()==0)
		||(!Character.isDigit(numEntryLine.charAt(0))))
			throw new IOException("Illegal numEntries: "+numEntryLine);
		final int numEntries = Integer.parseInt(numEntryLine);
		final byte[] rawDirBlock = new byte[(254 - bytesSoFar[0]) + ((headerSize-1) * 254)];
		int bytesRead = 0;
		while((in.available()>0) && (bytesRead < rawDirBlock.length))
		{
			int justRead = in.read(rawDirBlock, bytesRead, rawDirBlock.length-bytesRead);
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
			final String fileName =  getNextLYNXLineFromInputStream(bin, null); // don't trim, cuz spaces are valid.
			final String numBlockSz= getNextLYNXLineFromInputStream(bin, null).toUpperCase().trim();
			final String typChar =   getNextLYNXLineFromInputStream(bin, null).toUpperCase().trim();
			final String lastBlockSz=getNextLYNXLineFromInputStream(bin, null).toUpperCase().trim();
			if((fileName.length()==0)
			||(numBlockSz.length()==0)||(!Character.isDigit(numBlockSz.charAt(0)))
			||(typChar.length()==0)||(typChar.length()>3)
			||(lastBlockSz.length()==0)||(!Character.isDigit(lastBlockSz.charAt(0))))
				throw new IOException("Bad directory entry "+(i+1)+": "+fileName+"."+typChar+": "+numBlockSz+"("+lastBlockSz+")");
			FileInfo file = new FileInfo();
			file.fileName = fileName;
			file.fileType = FileType.fileType(typChar);
			file.size = ((Integer.valueOf(numBlockSz).intValue()-1) * 254) + Integer.valueOf(lastBlockSz).intValue();
			list.add(file);
		}
		for(FileInfo f : list)
		{
			int fbytesRead = 0;
			int numBlocks = (int)Math.round(Math.floor((double)f.size / 254.0));
			if((f.size % 254) > 0)
				numBlocks++;
			int allBlocksSize = numBlocks * 254;
			byte[] fileSubBytes = new byte[allBlocksSize];
			while((in.available()>0) && (fbytesRead < allBlocksSize))
			{
				int justRead = in.read(fileSubBytes, fbytesRead, allBlocksSize-fbytesRead);
				if(justRead < 0)
					break;
				if(justRead > 0)
					fbytesRead += justRead;
			}
			if(fbytesRead < allBlocksSize)
			{
				if((list.get(list.size()-1)!=f)
				||(fbytesRead < f.size-1024))
					throw new IOException("Incomplete data for "+f.fileName);
			}
			f.data = Arrays.copyOf(fileSubBytes, f.size);
		}
		return list;
	}
	
	public static List<FileInfo> getZipDeepContents(final File F) throws IOException
	{
		final ZipArchiveInputStream zin = new ZipArchiveInputStream(new FileInputStream(F));
		java.util.zip.ZipEntry entry = null;
		final List<FileInfo> list = new ArrayList<FileInfo>();
		while ((entry = zin.getNextZipEntry()) != null) 
		{
			final IMAGE_TYPE typeF1 = getImageTypeAndZipped(entry.getName());
			final List<FileInfo> fileData1;
			if(entry.getSize()<0)
			{
				errMsg(F.getName()+": Error: Bad -1 size :"+entry.getName());
				continue;
			}
			if(typeF1 != null)
			{
				final int[] f1Len=new int[1];
				byte[][][] diskF1=getDisk(typeF1,zin,entry.getName(),(int)entry.getSize(), f1Len);
				fileData1=getDiskFiles(entry.getName(),typeF1,diskF1,f1Len[0]);
				if(fileData1==null)
				{
					errMsg(F.getName()+": Error: Bad extension :"+entry.getName());
					continue;
				}
				diskF1=null;
				list.addAll(fileData1);
			}
			else
			if(getLooseImageTypeAndZipped(entry.getName()) != null)
			{
				fileData1 = new ArrayList<FileInfo>();
				try
				{
					fileData1.add(D64FileMatcher.getLooseFile(zin, entry.getName(), (int)entry.getSize()));
				}
				catch(final IOException e)
				{
					System.err.println(entry.getName()+": "+e.getMessage());
					continue;
				}
				list.addAll(fileData1);
			}
			else
			{
				// silently continue.. this could be common
				continue;
			}
		}
		zin.close();
		return list;
	}

	public static List<File> getAllFiles(final String filename, final int depth) throws IOException
	{
		java.util.regex.Pattern P=null;
		File baseF = new File(filename);
		if((filename.indexOf('*')>=0)||(filename.indexOf('+')>=0))
		{
			int x=filename.lastIndexOf(File.separator);
			if(x<=0)
				x=filename.lastIndexOf('/');
			if(x<=0)
				x=filename.lastIndexOf('\\');
			if(x>=0)
			{
				P=java.util.regex.Pattern.compile(filename.substring(x+1));
				baseF=new File(filename.substring(0,x));
			}
		}
		if(!baseF.exists())
			throw new IOException("No such file/directory: "+filename);
		return getAllFiles(baseF,P,depth);
	}

	public static List<File> getAllFiles(final File baseF, final Pattern P, final int depth)
	{
		final List<File> filesToDo=new LinkedList<File>();
		if(baseF.isDirectory())
		{
			try
			{
				final LinkedList<File> dirsLeft=new LinkedList<File>();
				dirsLeft.add(baseF);
				while(dirsLeft.size()>0)
				{
					final File dir=dirsLeft.removeFirst();
					for(final File F : dir.listFiles())
					{
						if(F.isDirectory())
						{
							int dep=0;
							File F1=F;
							while((F1 != null)&&(!F1.getCanonicalPath().equals(baseF.getCanonicalPath())))
							{
								F1=F1.getParentFile();
								dep++;
							}
							if(dep <= depth)
								dirsLeft.add(F);
						}
						else
						if((getImageTypeAndZipped(F)!=null)
						||(D64FileMatcher.getLooseImageTypeAndZipped(F)!=null)
						||(F.getName().toUpperCase().endsWith(".ZIP"))
						||(F.getName().toUpperCase().endsWith(".LNX")))
						{
							if((P==null)||(P.matcher(F.getName().subSequence(0, F.getName().length())).matches()))
								filesToDo.add(F);
						}
					}
				}
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.exit(-1);
			}
		}
		else
		if(getImageTypeAndZipped(baseF)!=null)
		{
			if((P==null)||(P.matcher(baseF.getName().subSequence(0, baseF.getName().length())).matches()))
				filesToDo.add(baseF);
		}
		return filesToDo;
	}

	public static List<FileInfo> getFileList(final File F)
	{
		int[] fLen=new int[1];
		byte[][][] diskF;
		List<FileInfo> fileData = null;
		final IMAGE_TYPE typeF = getImageTypeAndZipped(F);
		if(typeF != null)
		{
			diskF=getDisk(typeF,F,fLen);
			fileData=getDiskFiles(F.getName(),typeF,diskF,fLen[0]);
		}
		else
		if(getLooseImageTypeAndZipped(F) != null)
		{
			fileData = new ArrayList<FileInfo>();
			try
			{
				fileData.add(D64FileMatcher.getLooseFile(F));
			}
			catch(final IOException e)
			{
				System.err.println(F.getName()+": "+e.getMessage());
				return null;
			}
		}
		else
		if(F.getName().toUpperCase().endsWith(".ZIP"))
		{
			try
			{
				fileData = D64FileMatcher.getZipDeepContents(F);
			}
			catch(final IOException e)
			{
				System.err.println(F.getName()+": "+e.getMessage());
				return null;
			}
		}
		else
		if(F.getName().toUpperCase().endsWith(".LNX"))
		{
			try
			{
				fileData = D64FileMatcher.getLNXDeepContents(F);
			}
			catch(final IOException e)
			{
				System.err.println(F.getName()+": "+e.getMessage());
				return null;
			}
		}
		else
		{
			System.err.println("**** Error reading :"+F.getName());
			return null;
		}
		return fileData;
	}
	
	
	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64FileMatcher v1.1 (c)2017-2017 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64FileMatcher <file/path1> <file/path2>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search inside DNP");
			System.out.println("  -V verbose");
			System.out.println("  -P X verbose on disks with X percent matches");
			System.out.println("  -C use memory cache of all comparison files");
			System.out.println("  -D X Recursive depth X");
			System.out.println("");
			return;
		}
		final HashSet<COMP_FLAG> flags=new HashSet<COMP_FLAG>();
		String path=null;
		String expr="";
		int depth=Integer.MAX_VALUE;
		double pct=100.0;
		for(int i=0;i<args.length;i++)
		{
			if((args[i].startsWith("-")&&(path==null)))
			{
				for(int c=1;c<args[i].length();c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R':
						flags.add(COMP_FLAG.RECURSE);
						break;
					case 'v':
					case 'V':
						flags.add(COMP_FLAG.VERBOSE);
						break;
					case 'c':
					case 'C':
						flags.add(COMP_FLAG.CACHE);
						break;
					case 'd':
					case 'D':
						if(i<args.length-1)
						{
							depth=Integer.parseInt(args[i+1]);
							i++;
						}
						break;
					case 'p':
					case 'P':
						if(i<args.length-1)
						{
							pct=Double.parseDouble(args[i+1]);
							if(pct > 1)
								pct=pct/100.0;
							i++;
						}
						break;
					}
				}
			}
			else
			if(path==null)
				path=args[i];
			else
				expr+=" "+args[i];
		}
		expr=expr.trim();
		if(path==null)
		{
			System.err.println("Path1 not found!");
			System.exit(-1);
		}
		List<File> F1s=new ArrayList<File>(1);
		List<File> F2s=new ArrayList<File>(1);
		try {
			F1s = getAllFiles(path,depth);
			F2s = getAllFiles(expr,depth);
		} catch (final IOException e) {
			System.err.println(e.getMessage());
			System.exit(-1);
		}

		class D64Report
		{
			File diskF=null;
			boolean equal = false;
			FileInfo compareFD=null;
			public D64Report(final File diskF, final boolean equal, final FileInfo compareFD)
			{
				this.diskF=diskF;
				this.equal=equal;
				this.compareFD=compareFD;
			}
		}

		if(!flags.contains(COMP_FLAG.VERBOSE))
			System.setErr(new PrintStream(new OutputStream() {public void write(final int b) {}}));
		final Map<File,FMCache> cache=new TreeMap<File,FMCache>();
		for(final File F1 : F1s)
		{
			final Map<FileInfo,List<D64Report>> report = new HashMap<FileInfo,List<D64Report>>();
			final List<FileInfo> fileData1=D64FileMatcher.getFileList(F1);
			if(fileData1 == null)
			{
				System.err.println("Unable to process "+F1.getName());
				continue;
			}

			report.clear();
			for(final FileInfo fff : fileData1)
				report.put(fff, new LinkedList<D64Report>());
			for(final Iterator<File> f=F2s.iterator();f.hasNext();)
			{
				final File F2=f.next();
				//int[] f2Len;
				//byte[][][] diskF2;
				List<FileInfo> fileData2;
				if(cache.containsKey(F2))
				{
					//f2Len=cache.get(F2).fLen;
					//diskF2=cache.get(F2).diskF;
					fileData2=cache.get(F2).fileData1;
				}
				else
				{
					fileData2=D64FileMatcher.getFileList(F2);
					if(fileData2 == null)
					{
						f.remove();
						continue;
					}
					if(flags.contains(COMP_FLAG.CACHE))
					{
						final FMCache cacheEntry=new FMCache();
						//cacheEntry.diskF=diskF;
						//cacheEntry.fLen=[](int)F2.length();
						cacheEntry.fileData1=fileData2;
						cache.put(F2, cacheEntry);
					}
				}
				if(fileData2==null)
				{
					System.err.println("**** Bad extension :"+F2.getName());
					f.remove();
					continue;
				}
				for(final FileInfo f2 : fileData2)
				{
					for(final FileInfo f1 : fileData1)
					{
						if(f2.fileName.equals(f1.fileName))
						{
							final List<D64Report> rep = report.get(f1);
							if(!Arrays.equals(f1.data, f2.data))
								rep.add(new D64Report(F2,false,f2));
							else
								rep.add(new D64Report(F2,true,f2));
						}
						else
						if((f2.hash() == f1.hash())
						&&(Arrays.equals(f1.data, f2.data)))
						{
							final List<D64Report> rep = report.get(f1);
							rep.add(new D64Report(F2,true,f2));
						}
					}
				}
			}
			final StringBuilder str=new StringBuilder("Report on "+F1.getAbsolutePath()+":\n");
			if(flags.contains(D64FileMatcher.COMP_FLAG.VERBOSE))
				str.append("---------- Files Not Found for Diffing: \n");
			final StringBuilder subStr=new StringBuilder("");
			final List<FileInfo> sortedKeys = new ArrayList<FileInfo>();
			for(final FileInfo key : report.keySet())
				sortedKeys.add(key);
			Collections.sort(sortedKeys,new Comparator<FileInfo>() 
			{
				@Override
				public int compare(final FileInfo o1, final FileInfo o2) {
					return o1.filePath.compareTo(o2.filePath);
				}
			});
			subStr.setLength(0);
			int numFiles=0;
			int numMatchedAnywhere=0;
			final Map<File,int[]> reverseMatches=new HashMap<File,int[]>();
			for(final FileInfo key : sortedKeys)
			{
				if(key.fileType == FileType.DIR)
					continue;
				numFiles++;
				final List<D64Report> rep = report.get(key);
				final String fs1 = "  " + key.filePath+"("+key.fileType+"): "+(key.data==null?"null":Integer.toString(key.data.length));
				if(flags.contains(D64FileMatcher.COMP_FLAG.VERBOSE))
				{
					subStr.append(fs1).append("\n");
					if(rep.size()==0)
					{
						subStr.append("    N/A (No matches found on any target disks)").append("\n");
						continue;
					}
				}
				boolean hasMatch=false;
				double highestPercent=0.0;
				final double totalD=numFiles;
				for(final D64Report r : rep)
				{
					if(r.compareFD.fileType == FileType.DIR)
						continue;
					hasMatch = hasMatch || r.equal;
					if(r.equal)
					{
						if(!reverseMatches.containsKey(r.diskF))
							reverseMatches.put(r.diskF, new int[1]);
						reverseMatches.get(r.diskF)[0]++;
						final double d=reverseMatches.get(r.diskF)[0]/totalD;
						if(d > highestPercent)
							highestPercent = d;
					}
				}
				if(flags.contains(D64FileMatcher.COMP_FLAG.VERBOSE)
					||((highestPercent > pct)&&((pct < 100.0))))
				{

					for(final Iterator<File> f=F2s.iterator();f.hasNext();)
					{
						final File diskF=f.next();
						for(final D64Report r : rep)
						{
							if((r.diskF != diskF)
							||(r.compareFD.fileType == FileType.DIR))
								continue;
							if(pct < 100.0)
							{
								if(!reverseMatches.containsKey(r.diskF))
									continue;
								final double d=reverseMatches.get(r.diskF)[0]/totalD;
								if(d < pct)
									continue;
							}
							final String fs2 = r.compareFD.filePath+"("+r.compareFD.fileType+"): "+(r.compareFD.data==null?"null":Integer.toString(r.compareFD.data.length));
							int len=50;
							if(fs1.length()>len)
								len=fs1.length();
							int dlen=20;
							if(r.diskF.getName().length()>dlen)
								dlen=r.diskF.getName().length();
							subStr.append("    "+(r.equal?"MATCH:":"DIFF :")).append(fs1).append(spaces.substring(0,len-fs1.length())).append(fs2).append("  ("+r.diskF.getName()+")").append("\n");
						}
					}
				}
				if(hasMatch)
					numMatchedAnywhere++;
			}
			if(numMatchedAnywhere > 0)
			{
				subStr.append(numMatchedAnywhere+"/"+numFiles+" files matched somewhere.\n");
				for(final File f : reverseMatches.keySet())
					if(reverseMatches.get(f)[0] >= numFiles)
						subStr.append(reverseMatches.get(f)[0]+"/"+numFiles+" matched in "+f.getAbsolutePath()+"\n");
			}
			if(subStr.length()==0)
				str.append("No Matches!\n");
			else
				str.append(subStr.toString());
			System.out.println(str.toString());
		}
	}
}
