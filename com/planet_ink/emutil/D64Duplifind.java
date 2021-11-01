package com.planet_ink.emutil;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.util.zip.*;
/*
Copyright 2016-2017 Bo Zimmerman

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
public class D64Duplifind
{
	public enum SEARCH_FLAG {
		VERBOSE,
		RECURSE,
		DEEPCOMPARE
		;
	};

	public enum SHOW_FLAG {
		MATCHES1,
		MATCHES2,
		MISMATCHES1,
		MISMATCHES2,
		;
	};
	private static class DupFile
	{
		boolean contained=false;
		String filePath = null;
		File hostFile = null;
		long length = 0;
		String hash = null;
		public DupFile toDupFile()
		{
			return this;
		}
	}

	private static class DupFileExt extends DupFile
	{
		byte[] buf = null;
		public DupFile toDupFile()
		{
			super.toDupFile();
			final DupFile d=new DupFile();
			d.contained=contained;
			d.filePath=filePath;
			d.hostFile=hostFile;
			d.length=length;
			d.hash=hash;
			return d;
		}
	}

	public static short unsigned(final byte b)
	{
		return (short)(0xFF & b);
	}

	public static final String[] HEX=new String[256];
	public static final Hashtable<String,Short> ANTI_HEX=new Hashtable<String,Short>();
	public static final String HEX_DIG="0123456789ABCDEF";
	static{
		for(int h=0;h<16;h++)
			for(int h2=0;h2<16;h2++)
			{
				HEX[(h*16)+h2]=""+HEX_DIG.charAt(h)+HEX_DIG.charAt(h2);
				ANTI_HEX.put(HEX[(h*16)+h2],new Short((short)((h*16)+h2)));
			}
	}
	public static String toHex(final byte b){ return HEX[unsigned(b)];}
	public static String toHex(final byte[] buf){
		final StringBuffer ret=new StringBuffer("");
		for(int b=0;b<buf.length;b++)
			ret.append(toHex(buf[b]));
		return ret.toString();
	}

	public static short fromHex(final String hex)
	{
		return (ANTI_HEX.get(hex)).shortValue();
	}

	public static  boolean goodExt(String name)
	{
		name=name.toLowerCase();
		if(name.endsWith(".d64") || name.endsWith(".d81")
		|| name.endsWith(".d80") || name.endsWith(".d82")
		|| name.endsWith(".zip") || name.endsWith(".gz")
		|| name.endsWith(".lnx") || name.endsWith(".arc")
		|| name.endsWith(".tap") || name.endsWith(".t64")
		|| name.endsWith(".cvt") || name.endsWith(".d71")
		|| name.endsWith(".iso") || name.endsWith(".adf")
		|| name.endsWith(".dms") || name.endsWith(".lha")
		|| name.endsWith(".sid") || name.endsWith(".prg")
		|| name.endsWith(".ipf")
		)
			return true;
		return false;
	}

	public static void fill1PathFiles(final File path,final Set<SEARCH_FLAG> flags,final List<File> allPath1Files)
	{
		for(final File F : path.listFiles())
		{
			if(F.isDirectory())
			{
				if(flags.contains(SEARCH_FLAG.RECURSE))
					fill1PathFiles(F,flags,allPath1Files);
			}
			else
			if(goodExt(F.getName()))
				allPath1Files.add(F);
		}
	}

	public static boolean doZipEntry(final InputStream zip, final File F, final List<DupFileExt> files, final String name, final long size, final DupFileExt d, final byte[] lbuf) throws IOException, NoSuchAlgorithmException
	{
		int read=zip.read(lbuf);
		ByteBuffer bout = ByteBuffer.allocate((int)size);
		while((read>=0)&&(d.length < size))
		{
			d.length+=read;
			bout.put(lbuf,0,read);
			if(d.length < size)
			{
				long amtToRead = size-d.length;
				if(amtToRead > lbuf.length)
					amtToRead = lbuf.length;
				read=zip.read(lbuf,0,(int)amtToRead);
			}
		}
		final String ename = name.toLowerCase();
		if(goodExt(ename))
		{
			if(((bout.limit() != size || size != d.length)))
			{
				System.err.print("\r\nSize Match Failed: "+bout.limit()+"/"+size+": "+F.getAbsolutePath()+": "+name);
				return false;
			}
			d.buf=bout.array();
			d.contained=true;
			d.filePath=name;
			d.hostFile=F;
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(d.buf);
			final byte[] hash = digest.digest();
			d.hash=toHex(hash);
			files.add(d);
		}
		bout=null;
		return true;
	}

	public static List<DupFileExt> getFileStuff(final File F) throws Exception
	{
		final List<DupFileExt> files = new LinkedList<DupFileExt>();
		final String name=F.getName().toLowerCase();
		final byte[] lbuf = new byte[4096];
		if(name.endsWith(".zip"))
		{
			try
			{
				final ZipArchiveInputStream zip = new ZipArchiveInputStream(new FileInputStream(F));
				ZipArchiveEntry entry=zip.getNextZipEntry();
				while(entry!=null)
				{
					final DupFileExt d = new DupFileExt();
					if (entry.isDirectory())
					{
						entry = zip.getNextZipEntry();
						continue;
					}
					if(entry.getSize()>83361792)
					{
						zip.close();
						return files;
					}
					long size=entry.getSize();
					if(size < 0)
						size=174848;
					if(!doZipEntry(zip, F, files, entry.getName(), size, d, lbuf))
					{
						zip.close();
						final ZipInputStream zip2 = new ZipInputStream(new FileInputStream(F));
						ZipEntry entry2=zip2.getNextEntry();
						while(entry2!=null)
						{
							final DupFileExt d2 = new DupFileExt();
							if (entry2.isDirectory())
							{
								entry2 = zip2.getNextEntry();
								continue;
							}
							if(entry2.getSize()>83361792)
							{
								zip2.close();
								return files;
							}
							long size2=entry2.getSize();
							if(size2 < 0)
								size2=174848;
							if(!doZipEntry(zip2, F, files, entry2.getName(), size2, d2, lbuf))
							{
								zip2.close();
								break;
							}
							else
								entry2=zip2.getNextEntry();
						}
						zip2.close();
						break;
					}
					else
						entry=zip.getNextZipEntry();
				}
				zip.close();
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.err.print("\r\nFailed: "+F.getAbsolutePath()+": "+e.getMessage());
			}
		}
		else
		if(name.endsWith(".gz"))
		{
			try
			{
				final GzipCompressorInputStream in = new GzipCompressorInputStream(new FileInputStream(F));
				int read=in.read(lbuf);
				final DupFileExt d = new DupFileExt();
				ByteArrayOutputStream bout=new ByteArrayOutputStream((int)F.length()*2);
				while(read >= 0)
				{
					bout.write(lbuf,0,read);
					d.length += read;
					read=in.read(lbuf);
				}
				in.close();
				d.buf=bout.toByteArray();
				bout = null;
				d.contained=false;
				d.filePath=F.getAbsolutePath();
				d.hostFile=F.getAbsoluteFile();
				final MessageDigest digest = MessageDigest.getInstance("SHA-256");
				digest.update(d.buf);
				final byte[] hash = digest.digest();
				d.hash=toHex(hash);
				files.add(d);
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.err.print("\r\nGZFailed: "+F.getAbsolutePath());
			}
		}
		else
		{
			final FileInputStream in = new FileInputStream(F);
			int read=in.read(lbuf);
			final DupFileExt d = new DupFileExt();
			ByteBuffer bout=ByteBuffer.allocate((int)F.length());
			while(read >= 0)
			{
				d.length += read;
				bout.put(lbuf,0,read);
				read=in.read(lbuf);
			}
			in.close();
			d.buf=bout.array();
			bout=null;
			d.contained=false;
			d.filePath=F.getAbsolutePath();
			d.hostFile=F;
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(d.buf);
			final byte[] hash = digest.digest();
			d.hash=toHex(hash);
			files.add(d);
		}
		return files;
	}

	public static void catalog(final String key, final String filesPath, final List<File> allPathFiles, final HashSet<SEARCH_FLAG> flags)
	{
		final File path1F=new File(filesPath);
		try
		{
			final BufferedReader br=new BufferedReader(new FileReader(key+"cache.txt"));
			String s=br.readLine();
			if(s.equalsIgnoreCase(filesPath))
			{
				s=br.readLine();
				while(s!=null)
				{
					allPathFiles.add(new File(s));
					s=br.readLine();
				}
			}
			else
			{
				br.close();
				throw new FileNotFoundException();
			}
			br.close();
		} catch (final FileNotFoundException e2) {
			fill1PathFiles(path1F,flags,allPathFiles);
			try {
				final BufferedWriter bw=new BufferedWriter(new FileWriter(key+"cache.txt"));
				bw.write(filesPath+"\r\n");
				for(final File F : allPathFiles)
				{
					bw.write(F.getAbsolutePath()+"\r\n");
				}
				bw.close();
			} catch (final IOException e) {
				e.printStackTrace();
			}
		} catch (final IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	public static void catalogAndHash(final String key, final List<String> filesPaths, final List<File> allPathFiles, final HashSet<SEARCH_FLAG> flags, final Map<String,List<DupFile>> pathHashes)
	{
		String combinedPaths="";
		for(final String filesPath : filesPaths)
			combinedPaths+=" "+filesPath;
		combinedPaths=combinedPaths.trim();
		for(final String filesPath : filesPaths)
		{
			try
			{
				final BufferedReader br=new BufferedReader(new FileReader(key+"fullcache.txt"));
				String s=br.readLine();
				if(s.equalsIgnoreCase(combinedPaths))
				{
					s=br.readLine();
					while(s!=null)
					{
						final String[] parts=s.split("\\*");
						final DupFile d=new DupFile();
						final String hash=parts[0];
						d.contained=Boolean.valueOf(parts[1]).booleanValue();
						d.hostFile=new File(parts[2]);
						d.filePath=parts[3];
						d.length=Integer.valueOf(parts[4]).intValue();
						d.hash=hash;
						if(!pathHashes.containsKey(hash))
							pathHashes.put(hash, new LinkedList<DupFile>());
						pathHashes.get(hash).add(d);
						allPathFiles.add(new File(s));
						s=br.readLine();
					}
				}
				else
				{
					br.close();
					throw new FileNotFoundException();
				}
				br.close();
				return;
			} catch (final FileNotFoundException e1) {
				D64Duplifind.catalog(key, filesPath, allPathFiles, flags);
				if(flags.contains(SEARCH_FLAG.VERBOSE))
					System.out.print("Hashing "+key+" files...");
				int num = 0;
				final int everyDot = allPathFiles.size() / 50;
				int nextDot = everyDot;
				try
				{
					for(final File F : allPathFiles)
					{
						for(final DupFileExt d : getFileStuff(F))
						{
							if(!pathHashes.containsKey(d.hash))
								pathHashes.put(d.hash, new LinkedList<DupFile>());
							pathHashes.get(d.hash).add(d.toDupFile());
						}
						if(num++ > nextDot)
						{
							nextDot += everyDot;
							if(flags.contains(SEARCH_FLAG.VERBOSE))
								System.out.print(".");
						}
					}
					try {
						final BufferedWriter bw=new BufferedWriter(new FileWriter(key+"fullcache.txt"));
						bw.write(combinedPaths+"\r\n");
						for(final String hash : pathHashes.keySet())
						{
							for(final DupFile F : pathHashes.get(hash))
							{
								final StringBuilder str=new StringBuilder(hash+"*");
								str.append(F.contained).append("*");
								str.append(F.hostFile.getAbsolutePath()).append("*");
								str.append(F.filePath).append("*");
								str.append(F.length);
								bw.write(str.toString()+"\r\n");
							}
						}
						bw.close();
					} catch (final IOException e) {
						e.printStackTrace();
					}
				}
				catch (final Exception e)
				{
					e.printStackTrace();
				}
			} catch (final IOException e) {
				e.printStackTrace();
				System.exit(-1);
			}
		}
	}

	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Duplifind v"+D64Base.EMUTIL_VERSION+" (c)2016-"+D64Base.EMUTIL_AUTHOR);
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Duplifind <options>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search");
			System.out.println("  -V verbose");
			System.out.println("  -S matches1 matches2 mismatches1 mismatches2");
			//System.out.println("  -D deep compare");
			System.out.println("  -1 add to path1");
			System.out.println("  -2 add to path2");
			return;
		}
		final HashSet<SEARCH_FLAG> flags=new HashSet<SEARCH_FLAG>();
		final List<String> paths1=new LinkedList<String>();
		final List<String> paths2=new LinkedList<String>();
		final Set<SHOW_FLAG> shows=new HashSet<SHOW_FLAG>();
		for(int i=0;i<args.length;i++)
		{
			if(args[i].startsWith("-"))
			{
				for(int c=1;c<args[i].length();c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R':
						flags.add(SEARCH_FLAG.RECURSE);
						break;
					case 'v':
					case 'V':
						flags.add(SEARCH_FLAG.VERBOSE);
						break;
					case 'd':
					case 'D':
						flags.add(SEARCH_FLAG.DEEPCOMPARE);
						break;
					case '1':
						paths1.add(args[++i].trim());
						c=args[i].length();
						break;
					case '2':
						paths2.add(args[++i].trim());
						c=args[i].length();
						break;
					case 'S':
					case 's':
						c=args[i].length();
						i++;
						for(;i<args.length;i++)
						{
							if(args[i].startsWith("-"))
							{
								i--;
								break;
							}
							else
							{
								try
								{
									shows.add(SHOW_FLAG.valueOf(args[i].toUpperCase().trim()));
								}
								catch(final Exception e)
								{
									i--;
									break;
								}
							}
						}
						break;
					}
				}
			}
			else
			{
				System.err.println("Unknown: "+args[i]);
			}
		}
		if(paths1.size()==0)
		{
			System.err.println("No path1 defined!");
			return;
		}
		if(paths2.size()==0)
		{
			System.err.println("No path2 defined!");
			return;
		}

		for(final String path1 : paths1)
			if((path1==null)||(path1.length()==0)||(!new File(path1).exists())||(!new File(path1).isDirectory()))
			{
				System.err.println("Path1 '"+path1+"' not found!");
				return;
			}
		for(final String path2 : paths2)
			if((path2==null)||(path2.length()==0)||(!new File(path2).exists())||(!new File(path2).isDirectory()))
			{
				System.err.println("Path2 '"+path2+"' not found!");
				return;
			}
		final List<File> allPath1Files=new LinkedList<File>();
		final TreeMap<String,List<DupFile>> path1hashes=new TreeMap<String,List<DupFile>>();
		if(flags.contains(SEARCH_FLAG.VERBOSE))
			System.out.println("Cataloging path 1 files...");
		D64Duplifind.catalogAndHash("path1", paths1, allPath1Files, flags, path1hashes);

		System.out.println("");
		final List<File> allPath2Files=new LinkedList<File>();
		final TreeMap<String,List<DupFile>> path2hashes=new TreeMap<String,List<DupFile>>();
		if(flags.contains(SEARCH_FLAG.VERBOSE))
			System.out.print("Cataloging path 2 files...");
		D64Duplifind.catalogAndHash("path2", paths2, allPath2Files, flags, path2hashes);

		System.out.println("");
		try
		{
			final TreeMap<String,List<DupFile>> path2map = new TreeMap<String,List<DupFile>>();
			for(final String path2Hash : path2hashes.keySet())
			{
				final List<DupFile> path2FileSet=path2hashes.get(path2Hash);
				for(final Iterator<DupFile> d = path2FileSet.iterator();d.hasNext();)
				{
					final DupFile D=d.next();
					final String pathPrefix=D.hostFile.getParentFile().getAbsolutePath()+File.separator;
					if(!path2map.containsKey(pathPrefix))
						path2map.put(pathPrefix, new LinkedList<DupFile>());
					path2map.get(pathPrefix).add(D);
				}
			}
			if(shows.contains(SHOW_FLAG.MISMATCHES1))
			{
				for(final String hash : path1hashes.keySet())
				{
					for(final DupFile p1 : path1hashes.get(hash))
					{
						if(!path2hashes.containsKey(hash))
							System.out.println("Unatched "+p1.filePath);
					}
				}
			}
			for(final String prefixDir : path2map.keySet())
			{
				long totalFiles = 0;
				long matches = 0;
				for(final DupFile path2f : path2map.get(prefixDir))
				{
					totalFiles++;
					final List<DupFile> path1MatchesFound = path1hashes.get(path2f.hash);
					if(path1MatchesFound != null)
					{
						for(final DupFile dF : path1MatchesFound)
						{
							if(dF.length==path2f.length)
							{
								matches++;
								if(shows.contains(SHOW_FLAG.MATCHES1))
									System.out.println("Matched "+dF.filePath +" with "+path2f.filePath);
								else
								if(shows.contains(SHOW_FLAG.MATCHES2))
									System.out.println("Matched "+path2f.filePath +" with "+dF.filePath);
								break;
							}
						}
					}
					else
					if(shows.contains(SHOW_FLAG.MISMATCHES2))
						System.out.println("Unatched "+path2f.filePath);

				}
				final int pct=(int)Math.round((double)matches/(double)totalFiles*100.0);
				System.out.println("Matched: "+prefixDir+": "+matches+"/"+totalFiles + " ("+pct+"%)");
				if(pct>95)
				{
					for(final DupFile path2f : path2map.get(prefixDir))
					{
						totalFiles++;
						final List<DupFile> path1MatchesFound = path1hashes.get(path2f.hash);
						boolean found=false;
						if(path1MatchesFound != null)
						{
							for(final DupFile dF : path1MatchesFound)
							{
								if(dF.length==path2f.length)
								{
									found=true;
									break;
								}
							}
						}
						if(!found)
						{
							if(flags.contains(SEARCH_FLAG.VERBOSE))
							{
								System.out.println("   not found: "+path2f.hostFile.getAbsolutePath()+(path2f.contained?": "+path2f.filePath:""));
							}
						}
					}
				}
			}
		}
		catch(final Exception e)
		{
			e.printStackTrace();
		}

		if(flags.contains(SEARCH_FLAG.VERBOSE))
		{
			System.out.print("Done!");
		}
	}
}
