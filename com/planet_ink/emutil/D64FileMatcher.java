package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;

/*
Copyright 2017-2024 Bo Zimmerman

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
	public enum CompFlag
	{
		VERBOSE,
		RECURSE,
		NOSORT,
		CACHE
	};

	public enum FileFormat
	{
		PETSCII,
		ASCII,
		HEX;
	};

	private static class FMCache
	{
		List<FileInfo>	fileData1	= null;
	}

	public static List<FileInfo> getZipDeepContents(final File F, final BitSet parseFlags) throws IOException
	{
		final IOFile zipF = new IOFile(F);
		final List<FileInfo> list = new ArrayList<FileInfo>();
		for(final IOFile ioF : zipF.listFiles())
		{
			final List<FileInfo> fileData1;
			final CBMDiskImage diskF1 = new CBMDiskImage(ioF);
			if(diskF1.getType() != null)
			{
				fileData1=diskF1.getFiles(parseFlags);
				if(fileData1==null)
				{
					if(!parseFlags.get(PF_NOERRORS))
						errMsg(F.getName()+": Error: Bad extension :"+ioF.getName());
					continue;
				}
				list.addAll(fileData1);
			}
			else
			if(getLooseImageTypeAndGZipped(ioF.getName()) != null)
			{
				int size = (int)ioF.length();
				if(size < 0)
					size=MAGIC_MAX;
				fileData1 = new ArrayList<FileInfo>();
				try(InputStream in = ioF.createInputStream())
				{
					fileData1.add(getLooseFile(in, ioF.getName(), size));
				}
				catch(final IOException e)
				{
					System.err.println(ioF.getName()+": "+e.getMessage());
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
		zipF.close();
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
						if((CBMDiskImage.getImageTypeAndGZipped(F)!=null)
						||(getLooseImageTypeAndGZipped(F)!=null)
						||(F.getName().toUpperCase().endsWith(".ZIP")))
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
		if(CBMDiskImage.getImageTypeAndGZipped(baseF)!=null)
		{
			if((P==null)||(P.matcher(baseF.getName().subSequence(0, baseF.getName().length())).matches()))
				filesToDo.add(baseF);
		}
		else
		if(baseF.getName().toUpperCase().endsWith(".ZIP"))
		{
			if((P==null)||(P.matcher(baseF.getName().subSequence(0, baseF.getName().length())).matches()))
				filesToDo.add(baseF);
		}
		return filesToDo;
	}


	public static List<FileInfo> getFileList(final File F, final boolean normalizeForCompare, final BitSet parseFlags)
	{
		List<FileInfo> fileData = null;
		final CBMDiskImage.ImageType type = CBMDiskImage.getImageTypeAndGZipped(F);
		if(type != null)
		{
			final CBMDiskImage disk = new CBMDiskImage(F);
			fileData=disk.getFiles(parseFlags);
		}
		else
		if(getLooseImageTypeAndGZipped(F) != null)
		{
			fileData = new ArrayList<FileInfo>();
			try
			{
				fileData.add(getLooseFile(F));
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
				fileData = D64FileMatcher.getZipDeepContents(F,parseFlags);
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
				CBMDiskImage.normalizeCvt(f);
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
			System.out.println("  -Q Suppress parsing errors");
			System.out.println("  -En X exclude files matching mask 'X' in path 'n'");
			System.out.println("  -N No sorting of source filenames");
			System.out.println("");
			return;
		}
		final HashSet<CompFlag> flags=new HashSet<CompFlag>();
		int verboseLevel = 0;
		String path=null;
		String expr="";
		int depth=Integer.MAX_VALUE;
		int deeper = -1;
		double pct=100.0;
		final BitSet parseFlags = new BitSet();
		final List<Pattern> excludeMasks1 = new LinkedList<Pattern>();
		final List<Pattern> excludeMasks2 = new LinkedList<Pattern>();
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
						flags.add(CompFlag.RECURSE);
						break;
					case 'q':
					case 'Q':
						parseFlags.set(PF_NOERRORS);
						break;
					case 'n':
					case 'N':
						flags.add(CompFlag.NOSORT);
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
							List<Pattern> which = excludeMasks2;
							if(c<args[i].length()-1)
							{
								if(args[i].charAt(c+1)=='1')
									which = excludeMasks1;
							}
							final Pattern P=Pattern.compile(args[i+1]);
							which.add(P);
							i++;
							c=argLen;
						}
						break;
					case 'v':
					case 'V':
						flags.add(CompFlag.VERBOSE);
						verboseLevel++;
						break;
					case 'c':
					case 'C':
						flags.add(CompFlag.CACHE);
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
			F1s = getAllFiles(path,depth,excludeMasks1);
			F2s = getAllFiles(expr,depth,excludeMasks2);
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

		if(!flags.contains(CompFlag.VERBOSE))
			System.setErr(new PrintStream(new OutputStream() {public void write(final int b) {}}));
		Collections.sort(F1s,new Comparator<File>() {
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
			final List<FileInfo> fileData1=D64FileMatcher.getFileList(F1,true,parseFlags);
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
					fileData2=D64FileMatcher.getFileList(F2,true,parseFlags);
					if(fileData2 == null)
					{
						f.remove();
						continue;
					}
					if(flags.contains(CompFlag.CACHE))
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
						if(f2.fileName.toLowerCase().startsWith("combin")
						&&(f1.fileName.toLowerCase().startsWith("combin")))
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
			if(!flags.contains(D64FileMatcher.CompFlag.NOSORT))
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
				if(flags.contains(D64FileMatcher.CompFlag.VERBOSE))
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
				if(flags.contains(D64FileMatcher.CompFlag.VERBOSE)
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
