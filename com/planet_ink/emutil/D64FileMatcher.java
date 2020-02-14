package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import com.planet_ink.emutil.D64Base.FileInfo;
import com.planet_ink.emutil.D64Base.FileType;
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
		byte[][][]		diskF		= null;
		int[]			fLen		= null;
		List<FileInfo>	fileData1	= null;
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
						if(getImageTypeAndZipped(F)!=null)
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
			final IMAGE_TYPE typeF1 = getImageTypeAndZipped(F1);
			final List<FileInfo> fileData1;
			if(typeF1 != null)
			{
				final int[] f1Len=new int[1];
				byte[][][] diskF1=getDisk(typeF1,F1,f1Len);
				fileData1=getDiskFiles(F1.getName(),typeF1,diskF1,f1Len[0]);
				if(fileData1==null)
				{
					System.err.println("Bad extension :"+F1.getName());
					continue;
				}
				diskF1=null;
			}
			else
			if(getLooseImageTypeAndZipped(F1) != null)
			{
				fileData1 = new ArrayList<FileInfo>();
				try
				{
					fileData1.add(D64FileMatcher.getLooseFile(F1));
				}
				catch(final IOException e)
				{
					System.err.println(F1.getName()+": "+e.getMessage());
					continue;
				}
			}
			else
			{
				System.err.println("Error reading :"+F1.getName());
				continue;
			}

			report.clear();
			for(final FileInfo fff : fileData1)
				report.put(fff, new LinkedList<D64Report>());
			for(final Iterator<File> f=F2s.iterator();f.hasNext();)
			{
				final File F2=f.next();
				int[] f2Len;
				byte[][][] diskF2;
				List<FileInfo> fileData2;
				if(cache.containsKey(F2))
				{
					f2Len=cache.get(F2).fLen;
					diskF2=cache.get(F2).diskF;
					fileData2=cache.get(F2).fileData1;
				}
				else
				{
					f2Len=new int[1];
					final IMAGE_TYPE typeF2 = getImageTypeAndZipped(F2);
					if(typeF2 != null)
					{
						diskF2=getDisk(typeF2,F2,f2Len);
						fileData2=getDiskFiles(F2.getName(),typeF2,diskF2,f2Len[0]);
						if(flags.contains(COMP_FLAG.CACHE))
						{
							final FMCache cacheEntry=new FMCache();
							cacheEntry.diskF=diskF2;
							cacheEntry.fLen=f2Len;
							cacheEntry.fileData1=fileData2;
							cache.put(F2, cacheEntry);
						}
					}
					else
					if(getLooseImageTypeAndZipped(F2) != null)
					{
						fileData2 = new ArrayList<FileInfo>();
						try
						{
							fileData1.add(D64FileMatcher.getLooseFile(F2));
						}
						catch(final IOException e)
						{
							System.err.println(F2.getName()+": "+e.getMessage());
							continue;
						}
					}
					else
					{
						System.err.println("**** Error reading :"+F2.getName());
						f.remove();
						continue;
					}
				}
				if(fileData2==null)
				{
					System.err.println("**** Bad extension :"+F2.getName());
					f.remove();
					continue;
				}
				diskF2=null;
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
			Collections.sort(sortedKeys,new Comparator<FileInfo>() {
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
