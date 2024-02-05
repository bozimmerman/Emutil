package com.planet_ink.emutil;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
/*
Copyright 2006-2017 Bo Zimmerman

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
public class D64Search extends D64Mod
{
	public static class DatabaseInfo {
		String user=null;
		String pass=null;
		String className=null;
		String service=null;
		String table=null;
		Connection conn = null;
		PreparedStatement stmt = null;
		public boolean filled(){ return (user!=null)&&(pass!=null)&&(className!=null)&&(service!=null)&&(table!=null);}
	}

	public enum SEARCH_FLAG {
		CASESENSITIVE,
		VERBOSE,
		RECURSE,
		INSIDE,
		SHOWMD5,
		UNZIP,
		FULLPATH;
	};

	public enum FILE_FORMAT {
		PETSCII,
		ASCII,
		HEX;
	};

	private static boolean check(final String name, final char[] expr, final boolean caseSensitive)
	{
		int n=0;
		int ee=0;
		if((expr.length>1)&&(expr[0]=='%'))
		{
			for(;n<name.length();n++)
				if(name.charAt(n)==expr[1])
				{
					break;
				}
			ee=1;
		}
		for(int e=ee;e<expr.length;e++)
			switch(expr[e])
			{
			case '?':
				if(n+e>=name.length())
					return false;
				break;
			case '%':
				return true;
			default:
				if(n+e-ee>=name.length())
					return false;
				if(expr[e]!=name.charAt(n+e-ee))
					return false;
				break;
			}
		return name.length()-n==expr.length-ee;
	}

	private static boolean checkInside(byte[] buf, final char[] expr, final Set<SEARCH_FLAG> flags, final FILE_FORMAT fmt, final boolean caseSensitive)
	{
		if(!caseSensitive)
		{
			final byte[] chk=new byte[buf.length];
			for(int b=0;b<buf.length;b++)
				chk[b]=(byte)Character.toUpperCase((char)buf[b]);
			buf=chk;
		}
		final boolean byteFormat=fmt==FILE_FORMAT.HEX;
		int bb=0;
		int e=0;
		for(int b=0;b<buf.length;b++)
		{
			for(e=0,bb=0;e<=expr.length;e++,bb++)
				if(e==expr.length)
					return true;
				else
				if((expr[e]=='?')||(expr[e]=='%'))
					continue;
				else
				if((b+bb)>=buf.length)
					return false;
				else
				if(byteFormat)
				{
					if(e<expr.length-1)
					{
						if(buf[b+bb]!=D64Search.fromHex(""+expr[e]+expr[e+1]))
							break;
						e++;
					}
				}
				else
				if(expr[e]!=(char)buf[b+bb])
					break;
		}
		return false;
	}

	private static class SearchFile
	{
		public String filePath = "";
		public String fileName = "";
		public int diskLen = 0;
		public IMAGE_TYPE typ = null;
		public byte[][][] diskData = null;
	}

	private static List<SearchFile> parseSearchFiles(final File F, final boolean unzip)
	{
		final String name = F.getName().toUpperCase();
		List<SearchFile> files = null;
		if(name.endsWith(".GZ"))
		{
			if(!unzip)
				return null;
			for(final IMAGE_TYPE img : IMAGE_TYPE.values())
			{
				if(F.getName().toUpperCase().endsWith(img.toString()+".GZ"))
				{
					try
					{
						final FileInputStream fin = new FileInputStream(F);
						final int[] fileLen = new int[] {(int)F.length()};
						final byte[][][] disk = getDisk(img, fin, F.getName(), (int)F.length(), fileLen);
						final SearchFile sF = new SearchFile();
						sF.typ = img;
						sF.diskData = disk;
						sF.diskLen = fileLen[0];
						sF.fileName = F.getName();
						sF.filePath = F.getAbsolutePath();
						if(files == null)
							files = new ArrayList<SearchFile>();
						files.add(sF);
						fin.close();
					}
					catch(final IOException ioe)
					{
						System.err.print("\r\nFailed: "+F.getAbsolutePath()+": "+ioe.getMessage());
					}
					break;
				}
			}
		}
		else
		if(name.endsWith(".ZIP"))
		{
			if(!unzip)
				return null;
			try
			{
				final ZipArchiveInputStream zip = new ZipArchiveInputStream(new FileInputStream(F));
				ZipArchiveEntry entry=zip.getNextZipEntry();
				while(entry!=null)
				{
					if (entry.isDirectory())
					{
						entry = zip.getNextZipEntry();
						continue;
					}
					if(entry.getSize()>83361792)
					{
						zip.close();
						break;
					}
					IMAGE_TYPE img = null;
					for(final IMAGE_TYPE i : IMAGE_TYPE.values())
					{
						if(entry.getName().toUpperCase().endsWith(i.toString()))
						{
							img = i;
							break;
						}
					}
					if(img == null)
					{
						entry = zip.getNextZipEntry();
						continue;
					}
					final SearchFile sF = new SearchFile();
					final byte[] lbuf = new byte[4096];
					final int size = (entry.getSize()<1)?174848:(int)entry.getSize();
					int read=zip.read(lbuf);
					final ByteBuffer bout = ByteBuffer.allocate(size);
					sF.diskLen = read;
					while((read>=0)&&(sF.diskLen < size))
					{
						sF.diskLen+=read;
						bout.put(lbuf,0,read);
						if(sF.diskLen < size)
						{
							long amtToRead = size-sF.diskLen;
							if(amtToRead > lbuf.length)
								amtToRead = lbuf.length;
							read=zip.read(lbuf,0,(int)amtToRead);
						}
					}
					final int[] fileLen =new int[] { sF.diskLen } ;
					final InputStream is=new ByteArrayInputStream(bout.array());
					final byte[][][] disk = getDisk(img, is, F.getName()+"@"+entry.getName(), sF.diskLen, fileLen);
					sF.typ = img;
					sF.diskData = disk;
					sF.diskLen = fileLen[0];
					sF.fileName = F.getName()+"@"+entry.getName();
					sF.filePath = F.getAbsolutePath()+"@"+entry.getName();
					if(files == null)
						files = new ArrayList<SearchFile>();
					files.add(sF);
					entry = zip.getNextZipEntry();
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
		{
			for(final IMAGE_TYPE img : IMAGE_TYPE.values())
			{
				if(F.getName().toUpperCase().endsWith(img.toString()))
				{
					final SearchFile sF = new SearchFile();
					sF.typ = img;
					final int[] fLen=new int[1];
					final byte[][][] disk=getDisk(sF.typ,F,fLen);
					sF.diskData = disk;
					sF.diskLen = fLen[0];
					sF.fileName = F.getName();
					sF.filePath = F.getAbsolutePath();
					if(files == null)
						files = new ArrayList<SearchFile>();
					files.add(sF);
				}
			}
		}
		return files;
	}

	private static boolean searchDiskFiles(final List<SearchFile> files, final char[] expr,
			final Set<SEARCH_FLAG> flags, final FILE_FORMAT  fmt, final DatabaseInfo dbInfo,
			final MessageDigest MD)
	{
		final boolean caseSensitive=flags.contains(SEARCH_FLAG.CASESENSITIVE);
		final boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
		final boolean fullPath=flags.contains(SEARCH_FLAG.FULLPATH);
		boolean batchAdded=false;
		for(final SearchFile F : files)
		{
			final String fileName = fullPath?F.filePath:F.fileName;
			final byte[][][] disk = F.diskData;
			if(dbInfo!=null)
			{
				try
				{
					dbInfo.stmt.clearParameters();
					MD.reset();
					//ByteArrayOutputStream bout = new ByteArrayOutputStream();
					long diskLen=0;
					for(int b1=0;b1<disk.length;b1++)
					{
						for(int b2=0;b2<disk[b1].length;b2++)
						{
							MD.update(disk[b1][b2]);
							diskLen += disk[b1][b2].length;
							//bout.write(disk[b1][b2]);
						}
					}
					final byte[] md5 = MD.digest();
					dbInfo.stmt.setString(1, fileName);
					dbInfo.stmt.setString(2, "*");
					dbInfo.stmt.setInt(3, 0);
					dbInfo.stmt.setLong(4, diskLen);
					dbInfo.stmt.setString(5, toHex(md5));
					dbInfo.stmt.setString(6, "dsk");
					dbInfo.stmt.addBatch();
					batchAdded=true;
				}
				catch(final Exception e)
				{
					System.err.println("Stupid preparedStatement error: "+e.getMessage());
				}
			}
			final List<FileInfo> fileData=getFiles(fileName,F.typ,disk,F.diskLen);
			if((fmt==FILE_FORMAT.PETSCII)||inside)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append(D64Search.convertToPetscii(b));
					info.fileName = str.toString();
				}
			}
			else
			if(fmt==FILE_FORMAT.ASCII)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append((char)b);
					info.fileName = str.toString();
				}
			}
			else
			if(fmt==FILE_FORMAT.ASCII)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append(toHex(b));
					info.fileName = str.toString();
				}
			}
			if(fmt==FILE_FORMAT.PETSCII)
			{
				for(final FileInfo info : fileData)
				{
					if(info.data!=null)
					{
						for(int i=0;i<info.data.length;i++)
							info.data[i]=(byte)(D64Search.convertToPetscii(info.data[i]) & 0xff);
					}
				}
			}

			if(fileData==null)
			{
				System.err.println("Error reading :"+fileName);
				continue;
			}
			boolean announced=false;
			byte[] md5 = null;
			for(int n=0;n<fileData.size();n++)
			{
				md5=null;
				final FileInfo f = fileData.get(n);
				if(f.data==null)
					continue;
				if((inside&&(checkInside(f.data,expr,flags,fmt,caseSensitive)))
				||check(caseSensitive?f.fileName:f.fileName.toUpperCase(),expr,caseSensitive))
				{
					if(!announced)
					{
						System.out.println(fileName);
						announced=true;
					}
					String name=f.fileName;
					final StringBuffer asciiName=new StringBuffer("");
					for(int x=0;x<name.length();x++)
						asciiName.append(D64Search.convertToPetscii((byte)name.charAt(x)));
					if(dbInfo!=null)
					{
						MD.reset();
						MD.update(f.data);
						md5 = MD.digest();
						try
						{
							dbInfo.stmt.clearParameters();
							dbInfo.stmt.setString(1, fileName);
							dbInfo.stmt.setString(2, asciiName.toString());
							dbInfo.stmt.setInt(3, (n+1));
							dbInfo.stmt.setInt(4, f.size);
							dbInfo.stmt.setString(5, toHex(md5));
							dbInfo.stmt.setString(6, f.fileType.toString().toLowerCase());
							dbInfo.stmt.addBatch();
							batchAdded=true;
						}
						catch(final SQLException e)
						{
							System.err.println("Stupid preparedStatement error: "+e.getMessage());
						}
					}
					if(!flags.contains(SEARCH_FLAG.INSIDE))
					{
						if(fmt==FILE_FORMAT.ASCII)
							name=asciiName.toString();
						else
						if(fmt==FILE_FORMAT.HEX)
						{
							final StringBuffer newName=new StringBuffer("");
							for(int x=0;x<name.length();x+=2)
								newName.append((char)fromHex(name.substring(0,2)));
							name=newName.toString();
						}
					}
					System.out.print("  "+asciiName.toString());
					if((flags.contains(SEARCH_FLAG.VERBOSE)))
						System.out.print(","+f.fileType+", "+f.size+" bytes");
					if((flags.contains(SEARCH_FLAG.SHOWMD5)))
					{
						if(md5==null)
						{
							MD.reset();
							MD.update(f.data);
							md5=MD.digest();
						}
						System.out.print(", MD5: "+toHex(md5));
					}
					System.out.println("");
				}
			}
		}
		return batchAdded;
	}

	private static void searchFilesystem(final File F, final char[] expr, final Set<SEARCH_FLAG> flags,
			final FILE_FORMAT  fmt, final DatabaseInfo dbInfo, final TreeSet<File> pathsDone)
	{
		if(F.isDirectory())
		{
			if(flags.contains(SEARCH_FLAG.RECURSE)
			&&(!pathsDone.contains(F)))
			{
				pathsDone.add(F);
				final File[] files=F.listFiles();
				for(int f=0;f<files.length;f++)
					searchFilesystem(files[f],expr,flags,fmt, dbInfo,pathsDone);
			}
		}
		else
		{
			MessageDigest MD=null;
			if(flags.contains(SEARCH_FLAG.SHOWMD5))
			{
				try{MD=MessageDigest.getInstance("MD5");}catch(final Exception e){e.printStackTrace(System.err);}
			}
			final boolean unzip = flags.contains(SEARCH_FLAG.UNZIP);
			final List<SearchFile> files = parseSearchFiles(F,unzip);
			if(files != null)
			{
				if(searchDiskFiles(files,expr,flags,fmt,dbInfo,MD))
				{
					try
					{
						dbInfo.stmt.executeBatch();
					}
					catch(final SQLException e)
					{
						System.err.println("SQL preparedStatement execute batch error: "+e.getMessage());
					}
				}
			}
		}
	}

	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Search v"+EMUTIL_VERSION+" (c)2006-"+EMUTIL_AUTHOR);
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Search <options> <path> <expression>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive path search");
			System.out.println("  -V verbose");
			System.out.println("  -M show MD5 sum for each matching file");
			System.out.println("  -C case sensitive");
			System.out.println("  -Z unzip archives");
			System.out.println("  -F full absolute paths");
			System.out.println("  -X expr fmt (-Xp=petscii, Xa=ascii, Xh=hex)");
			System.out.println("  -I search inside files (substring search)");
			System.out.println("  -D db export of disk info data (-Du<user>,");
			System.out.println("     -Dp<password>, -Dc<java class>,");
			System.out.println("     -Ds<service> -Dt<tablename>)");
			System.out.println("     (Columns: string imagepath,");
			System.out.println("               string filename, int filenum,");
			System.out.println("               long size, string md5,");
			System.out.println("               string filetype)");
			System.out.println("");
			System.out.println("");
			System.out.println("* Expressions include % and ? characters.");
			System.out.println("* Hex expressions include hex digits, *, and ?.");
			return;
		}
		final Set<SEARCH_FLAG> flags=new HashSet<SEARCH_FLAG>();
		FILE_FORMAT fmt=FILE_FORMAT.PETSCII;
		String path=null;
		String expr="";
		DatabaseInfo dbInfo = null;
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
						flags.add(SEARCH_FLAG.RECURSE);
						break;
					case 'c':
					case 'C':
						flags.add(SEARCH_FLAG.CASESENSITIVE);
						break;
					case 'v':
					case 'V':
						flags.add(SEARCH_FLAG.VERBOSE);
						break;
					case 'i':
					case 'I':
						flags.add(SEARCH_FLAG.INSIDE);
						break;
					case 'z':
					case 'Z':
						flags.add(SEARCH_FLAG.UNZIP);
						break;
					case 'm':
					case 'M':
						flags.add(SEARCH_FLAG.SHOWMD5);
						break;
					case 'f':
					case 'F':
						flags.add(SEARCH_FLAG.FULLPATH);
						break;
					case 'x':
					case 'X':
					{
						final int x=(c<args[i].length()-1)?"PAH".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.err.println("Error: -x  must be followed by a,p,or h");
							return;
						}
						fmt=FILE_FORMAT.values()[x];
						break;
					}
					case 'd':
					case 'D':
					{
						final int x=(c<args[i].length()-1)?"UPCST".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.err.println("Error: -d  must be followed by u, p, c, s, or t");
							return;
						}
						if(dbInfo==null) dbInfo = new DatabaseInfo();
						switch(Character.toLowerCase(args[i].charAt(c+1)))
						{
						case 'u':
							dbInfo.user = args[i].substring(c + 2);
							break;
						case 'p':
							dbInfo.pass = args[i].substring(c + 2);
							break;
						case 'c':
							dbInfo.className = args[i].substring(c + 2);
							break;
						case 's':
							dbInfo.service = args[i].substring(c + 2);
							break;
						case 't':
							dbInfo.table = args[i].substring(c + 2);
							break;
						}
						c=args[i].length()-1;
						break;
					}
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
		if((expr.length()>1)&&(expr.startsWith("%"))
		&&((expr.charAt(1)=='%')||(expr.charAt(1)=='?')))
		{
			System.err.println("illegal %? expression error.");
			return;
		}
		if(path==null)
		{
			System.err.println("Path not found!");
			return;
		}
		if(dbInfo!=null)
		{
			System.out.println("DBInfo:\n");
			System.out.println("user	 :" + dbInfo.user);
			System.out.println("pass	 :" + dbInfo.pass);
			System.out.println("className:" + dbInfo.className);
			System.out.println("service  :" + dbInfo.service);
			System.out.println("table	:" + dbInfo.table);
			if(!dbInfo.filled())
			{
				System.err.println("DBInfo incomplete!");
				return;
			}
			flags.add(SEARCH_FLAG.VERBOSE);
			flags.add(SEARCH_FLAG.SHOWMD5);
			try
			{
				Class.forName(dbInfo.className);
				dbInfo.conn = DriverManager.getConnection(dbInfo.service,dbInfo.user,dbInfo.pass );
				dbInfo.stmt=dbInfo.conn.prepareStatement("insert into "+dbInfo.table+" (imagepath, filename, filenum,size, md5, filetype) values (?,?,?,?,?,?)");
			}
			catch(final Exception e)
			{
				System.err.println("Unable to connect to database: "+e.getMessage());
				return;
			}
		}
		final char[] exprCom=expr.toCharArray();
		if((!flags.contains(SEARCH_FLAG.CASESENSITIVE))||(fmt==FILE_FORMAT.HEX))
			for(int e=0;e<exprCom.length;e++)
				exprCom[e]=Character.toUpperCase(exprCom[e]);
		if(fmt==FILE_FORMAT.HEX)
		{
			for(int e=0;e<exprCom.length;e++)
				if((exprCom[e]!='?')&&(exprCom[e]!='%')&&(HEX_DIG.indexOf(exprCom[e])<0))
				{
					System.err.println("Illegal hex '"+exprCom[e]+"' in expression.");
					return;
				}
		}
		final File F=new File(path);
		final TreeSet<File> pathsDone = new TreeSet<File>();
		if(F.isDirectory())
		{
			final File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				searchFilesystem(files[f],exprCom,flags,fmt,dbInfo, pathsDone);
		}
		else
			searchFilesystem(F,exprCom,flags,fmt, dbInfo, pathsDone);
	}
}
