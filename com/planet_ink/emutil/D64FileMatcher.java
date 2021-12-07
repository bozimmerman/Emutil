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
		NOSORT,
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
		final FileInputStream fin=new FileInputStream(F);
		try
		{
			//final long fileLen = F.length();
			return getLNXDeepContents(fin);
		}
		finally
		{
			fin.close();
		}
	}

	public static String getNextLYNXLineFromInputStream(final InputStream in, final int[] bytesRead, final OutputStream bout) throws IOException
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

	public static boolean isInteger(final String s)
	{
		try
		{
			final int x=Integer.parseInt(s);
			return x>0;
		}
		catch(final Exception e)
		{
			return false;
		}

	}

	public static List<FileInfo> getLNXDeepContents(final InputStream in) throws IOException
	{
		final List<FileInfo> list = new ArrayList<FileInfo>();
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
		if(isInteger(splitSig[splitSig.length-1])
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
			file.rawFileName = fout.toByteArray();
			file.fileType = FileType.fileType(typChar);
			file.size = ((Integer.valueOf(numBlockSz).intValue()-1) * 254) + Integer.valueOf(lastBlockSz).intValue()-1;
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

	public static List<FileInfo> getZipDeepContents(final File F) throws IOException
	{
		final ZipArchiveInputStream zin = new ZipArchiveInputStream(new FileInputStream(F));
		java.util.zip.ZipEntry entry = null;
		final List<FileInfo> list = new ArrayList<FileInfo>();
		while ((entry = zin.getNextZipEntry()) != null)
		{
			final List<FileInfo> fileData1;
			if(entry.getName().toUpperCase().endsWith(".LNX")) // loose file conditions
			{
				int size = (int)entry.getSize();
				if(size < 0)
					size=MAGIC_MAX;
				fileData1 = new ArrayList<FileInfo>();
				try
				{
					fileData1.add(D64FileMatcher.getLooseFile(zin, entry.getName(), size));
				}
				catch(final IOException e)
				{
					System.err.println(entry.getName()+": "+e.getMessage());
					continue;
				}
				list.addAll(fileData1);
				continue;
			}
			final IMAGE_TYPE typeF1 = getImageTypeAndZipped(entry.getName());
			if(typeF1 != null)
			{
				if(entry.getSize()<0)
				{
					errMsg(F.getName()+": Error: Bad -1 size :"+entry.getName());
					continue;
				}
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
				int size = (int)entry.getSize();
				if(size < 0)
					size=MAGIC_MAX;
				fileData1 = new ArrayList<FileInfo>();
				try
				{
					fileData1.add(D64FileMatcher.getLooseFile(zin, entry.getName(), size));
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

	public static List<File> getAllFiles(final String filename, final int depth, final List<Pattern> excl) throws IOException
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
		return getAllFiles(baseF,P,depth,excl);
	}

	public static List<File> getAllFiles(final File baseF, final Pattern P, final int depth, final List<Pattern> excl)
	{
		final List<File> filesToDo=new LinkedList<File>();
		if(baseF.isDirectory())
		{
			try
			{
				final LinkedList<File> dirsLeft=new LinkedList<File>();
				dirsLeft.add(baseF);
				final boolean doExcl = excl != null && excl.size()>0;
				while(dirsLeft.size()>0)
				{
					final File dir=dirsLeft.removeFirst();
					for(final File F : dir.listFiles())
					{
						if(doExcl && (excl != null))
						{
							final String path = F.getAbsolutePath();
							boolean excluded=false;
							for(final Pattern P1 : excl)
							{
								if(P1.matcher(path).find())
								{
									excluded=true;
									break;
								}
							}
							if(excluded)
								continue;
						}

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


	public static List<FileInfo> getFileList(final File F, final boolean normalizeForCompare)
	{
		final int[] fLen=new int[1];
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
				final FileInfo iF=D64FileMatcher.getLooseFile(F);
				if((iF.fileName!=null)
				&& iF.fileName.toUpperCase().endsWith(".LNX")
				&& (iF.data!=null))
				{
					final ByteArrayInputStream fin=new ByteArrayInputStream(iF.data);
					try
					{
						fileData.addAll(getLNXDeepContents(fin));
					}
					finally
					{
						fin.close();
					}
				}
				else
					fileData.add(iF);
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
				final List<FileInfo> readSet = new ArrayList<FileInfo>(fileData);
				for(final FileInfo iF : readSet)
				{
					if((iF.fileName!=null)
					&& iF.fileName.toUpperCase().endsWith(".LNX")
					&& (iF.data!=null))
					{
						fileData.remove(iF);
						final ByteArrayInputStream fin=new ByteArrayInputStream(iF.data);
						try
						{
							fileData.addAll(getLNXDeepContents(fin));
						}
						finally
						{
							fin.close();
						}
					}
				}
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
		if(normalizeForCompare)
		{
			for(final FileInfo f : fileData)
				normalizeCvt(f);
		}
		return fileData;
	}

	final static byte[] cvtSignature = " formatted GEOS file V1.0".getBytes();

	public static boolean isCvt(final FileInfo f)
	{
		if((f.data!=null)&&(f.data.length>512))
		{
			if(f.fileName.toLowerCase().endsWith(".cvt"))
				return true;
			if((f.data[33]==' ')
			&&(f.data[34]=='f')
			&&(Arrays.equals(Arrays.copyOfRange(f.data,33,33+cvtSignature.length),cvtSignature)))
				return true;
		}
		return false;
	}

	public static boolean filledBytes(final byte[] buf)
	{
		final byte b=buf[0];
		for(int i=1;i<buf.length;i++)
			if(buf[i]!=b)
				return false;
		return true;
	}

	public static boolean equalRange(final byte[] buf1, final byte[] buf2, final int start, final int length)
	{
		final int end=start+length;
		for(int i=start;i<end;i++)
			if(buf1[i] != buf2[i])
				return false;
		return true;
	}

	public static boolean areEqual(final FileInfo f1, final FileInfo f2)
	{
		if((f2.hash() == f1.hash())
		&&(f1.data!=null)&&(f2.data!=null)
		&&(f1.data.length>0)&&(f2.data.length>0)
		&&(Arrays.equals(f1.data, f2.data)))
			return true;
		if(isCvt(f1)
		&& isCvt(f2)
		&&(Math.abs(f1.data.length - f2.data.length) < 257)
		&&(equalRange(f1.data,f2.data,254,254)))
		{
			final byte[] oldData1 = f1.data;
			final byte[] oldData2 = f2.data;
			if((oldData1.length>oldData2.length)
			&&(filledBytes(Arrays.copyOfRange(oldData1, oldData2.length, oldData1.length))))
			{
				f1.data=Arrays.copyOfRange(oldData1,0, oldData2.length);
				f2.data=Arrays.copyOfRange(oldData2,0, oldData2.length);
			}
			else
			if((oldData1.length<oldData2.length)
			&&(filledBytes(Arrays.copyOfRange(oldData2, oldData1.length, oldData2.length))))
			{
				f1.data=Arrays.copyOfRange(oldData1,0, oldData2.length);
				f2.data=Arrays.copyOfRange(oldData2,0, oldData2.length);
			}
			for(int i=0;i<254;i++)
				f2.data[i]=f1.data[i];
			if(Arrays.equals(f1.data, f2.data))
			{
				f1.reset();
				f1.hash();
				f2.reset();
				f2.hash();
				FileInfo.hashCompare(f1, f2);
				return true;
			}
			f1.data=oldData1;
			f2.data=oldData2;
		}
		return false;
	}


	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64FileMatcher v"+EMUTIL_VERSION+" (c)2017-"+EMUTIL_AUTHOR);
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64FileMatcher <file/path1> <file/path2>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search inside DNP");
			System.out.println("  -A X approx matching min X% (slower)");
			System.out.println("  -V verbose");
			System.out.println("  -P X verbose on disks with X percent matches");
			System.out.println("  -C use memory cache of all comparison files");
			System.out.println("  -D X Recursive depth X");
			System.out.println("  -E X exclude files matching mask 'X'");
			System.out.println("  -N No sorting of source filenames");
			System.out.println("");
			return;
		}
		final HashSet<COMP_FLAG> flags=new HashSet<COMP_FLAG>();
		int verboseLevel = 0;
		String path=null;
		String expr="";
		int depth=Integer.MAX_VALUE;
		int deeper = -1;
		double pct=100.0;
		final List<Pattern> excludeMasks = new LinkedList<Pattern>();
		for(int i=0;i<args.length;i++)
		{
			final int argLen = args[i].length();
			if((args[i].startsWith("-")&&(path==null)))
			{
				for(int c=1;c<argLen;c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R':
						flags.add(COMP_FLAG.RECURSE);
						break;
					case 'n':
					case 'N':
						flags.add(COMP_FLAG.NOSORT);
						break;
					case 'a':
					case 'A':
						if(i<args.length-1)
						{
							deeper=Integer.parseInt(args[i+1]);
							i++;
							c=argLen;
						}
						break;
					case 'e':
					case 'E':
						if(i<args.length-1)
						{
							final Pattern P=Pattern.compile(args[i+1]);
							excludeMasks.add(P);
							i++;
							c=argLen;
						}
						break;
					case 'v':
					case 'V':
						flags.add(COMP_FLAG.VERBOSE);
						verboseLevel++;
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
							c=argLen;
						}
						break;
					case 'p':
					case 'P':
						if(i<args.length-1)
						{
							pct=Double.parseDouble(args[i+1]);
							if(pct > 1)
								pct=pct/100.0;
							c=argLen;
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
			F1s = getAllFiles(path,depth,excludeMasks);
			F2s = getAllFiles(expr,depth,excludeMasks);
		} catch (final IOException e) {
			System.err.println(e.getMessage());
			System.exit(-1);
		}

		class D64Report
		{
			File diskF=null;
			boolean equal = false;
			FileInfo compareFD=null;
			int approx;
			public D64Report(final File diskF, final boolean equal, final FileInfo compareFD)
			{
				this.diskF=diskF;
				this.equal=equal;
				this.compareFD=compareFD;
			}
			public D64Report(final File diskF, final boolean equal, final FileInfo compareFD, final int approx)
			{
				this(diskF, equal, compareFD);
				this.approx=approx;
			}
		}

		if(!flags.contains(COMP_FLAG.VERBOSE))
			System.setErr(new PrintStream(new OutputStream() {public void write(final int b) {}}));
		F1s.sort(new Comparator<File>() {
			@Override
			public int compare(final File o1, final File o2)
			{
				if(o1.getParent().equalsIgnoreCase(o2.getParent()))
					return o1.getName().compareToIgnoreCase(o2.getName());
				return o1.getParent().compareToIgnoreCase(o2.getParent());
			}
		});
		final Map<File,FMCache> cache=new TreeMap<File,FMCache>();
		for(final File F1 : F1s)
		{
			final Map<FileInfo,List<D64Report>> report = new HashMap<FileInfo,List<D64Report>>();
			final Map<FileInfo,List<D64Report>> approxs=new HashMap<FileInfo,List<D64Report>>();
			final List<FileInfo> fileData1=D64FileMatcher.getFileList(F1,true);
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
				if(F2.getAbsolutePath().equals(F1.getAbsolutePath()))
					continue;
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
					fileData2=D64FileMatcher.getFileList(F2,true);
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
						final List<D64Report> rep = report.get(f1);
						/*
						if(f2.fileName.toLowerCase().startsWith("combin")&&(f1.fileName.toLowerCase().startsWith("combin")))
						{
							try {
								FileOutputStream fo=new FileOutputStream("c:\\tmp\\a.bin");
								fo.write(f1.data); fo.close();
								fo=new FileOutputStream("c:\\tmp\\b.bin");
								fo.write(f2.data); fo.close();
							} catch(Exception e) {}
						}
						*/
						if(areEqual(f1,f2))
							rep.add(new D64Report(F2,true,f2));
						else
						if((deeper > -1)
						&&(f1.data!=null)&&(f2.data!=null)
						&&(f1.data.length>0)&&(f2.data.length>0))
						{
							final int hp=FileInfo.hashCompare(f1, f2);
							if(hp >= deeper)
							{
								if(!approxs.containsKey(f1))
								{
									final ArrayList<D64Report> n = new ArrayList<D64Report>();
									n.add(new D64Report(F2,true,f2,hp));
									approxs.put(f1, n);
								}
								else
									approxs.get(f1).add(new D64Report(F2,true,f2,hp));
							}
							else
							if(f2.fileName.equals(f1.fileName))
								rep.add(new D64Report(F2,false,f2,hp));
						}
						else
						if(f2.fileName.equals(f1.fileName))
							rep.add(new D64Report(F2,false,f2));
					}
				}
			}
			for(final FileInfo f1 : approxs.keySet())
			{
				if(approxs.containsKey(f1))
				{
					final List<D64Report> rep = report.get(f1);
					for(final D64Report fr : approxs.get(f1))
					{
						if(fr.equal)
							rep.add(fr);
						else
						{
							boolean matched=false;
							for(final D64Report r : rep)
								matched = matched | r.equal;
							if(!matched)
								rep.add(fr);
						}
					}
				}
			}
			final StringBuilder str=new StringBuilder("Report on "+F1.getAbsolutePath()+":\n");
			final StringBuilder subStr=new StringBuilder("");
			final List<FileInfo> sortedKeys = new ArrayList<FileInfo>();
			for(final FileInfo key : report.keySet())
				sortedKeys.add(key);
			if(!flags.contains(D64FileMatcher.COMP_FLAG.NOSORT))
			{
				Collections.sort(sortedKeys,new Comparator<FileInfo>()
				{
					@Override
					public int compare(final FileInfo o1, final FileInfo o2) {
						return o1.filePath.compareToIgnoreCase(o2.filePath);
					}
				});
			}
			else
			{
				sortedKeys.clear();
				for(final FileInfo f1 : fileData1)
				{
					if(report.containsKey(f1))
						sortedKeys.add(f1);
				}
			}
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
				final String fs1 = "  " + key.fileName+"("+key.fileType+"): "+(key.data==null?"null":Integer.toString(key.data.length));
				if(flags.contains(D64FileMatcher.COMP_FLAG.VERBOSE))
				{
					if(rep.size()==0)
					{
						if(verboseLevel > 1)
						{
							subStr.append(fs1).append("\n");
							subStr.append("    N/A (No matches found on any target disks)").append("\n");
						}
						continue;
					}
					subStr.append(fs1).append("\n");
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
							final String matchStr;
							if(r.equal)
								matchStr=(r.approx > 0)?"M-"+r.approx+"%":"MATCH";
							else
							if(r.approx>0)
								matchStr=(""+r.approx+"%     ").substring(0,5);
							else
								matchStr="DIFF";
							subStr.append("    "+matchStr+":").append(fs1).append(spaces.substring(0,len-fs1.length())).append(fs2).append("  ("+r.diskF.getName()+")").append("\n");
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
