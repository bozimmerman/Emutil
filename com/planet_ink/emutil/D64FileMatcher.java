package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
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
	};
	public enum FILE_FORMAT {
		PETSCII,
		ASCII,
		HEX;
	};
	
	
	public static List<File> getAllFiles(String filename, int depth) throws IOException
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

	public static List<File> getAllFiles(File baseF, Pattern P, int depth)
	{
		List<File> filesToDo=new LinkedList<File>();
		if(baseF.isDirectory())
		{
			try
			{
				LinkedList<File> dirsLeft=new LinkedList<File>();
				dirsLeft.add(baseF);
				while(dirsLeft.size()>0)
				{
					File dir=dirsLeft.removeFirst();
					for(File F : dir.listFiles())
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
			catch(Exception e)
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
	
	public static void main(String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64FileMatcher v1.0 (c)2017-2017 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64FileMatcher <file/path1> <file/path2>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search inside DNP");
			System.out.println("  -V verbose");
			System.out.println("  -D X Recursive depth X");
			System.out.println("");
			return;
		}
		HashSet<COMP_FLAG> flags=new HashSet<COMP_FLAG>();
		String path=null;
		String expr="";
		int depth=Integer.MAX_VALUE;
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
					case 'd':
					case 'D':
						if(i<args.length-1)
						{
							depth=Integer.parseInt(args[i+1]);
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
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.exit(-1);
		}
		
		class D64Report
		{
			File diskF=null;
			boolean equal = false;
			FileInfo compareFD=null;
			public D64Report(File diskF, boolean equal, FileInfo compareFD)
			{
				this.diskF=diskF;
				this.equal=equal;
				this.compareFD=compareFD;
			}
		}
		
		for(File F1 : F1s)
		{
			Map<FileInfo,List<D64Report>> report = new HashMap<FileInfo,List<D64Report>>();
			IMAGE_TYPE typeF1 = getImageTypeAndZipped(F1);
			if(typeF1==null)
			{
				System.err.println("Error reading :"+F1.getName());
				continue;
			}
			int[] f1Len=new int[1];
			byte[][][] diskF1=getDisk(typeF1,F1,f1Len);
			List<FileInfo> fileData1=getDiskFiles(F1.getName(),typeF1,diskF1,f1Len[0]);
			if(fileData1==null)
			{
				System.err.println("Bad extension :"+F1.getName());
				continue;
			}
			
			diskF1=null;
			report.clear();
			for(FileInfo fff : fileData1)
				report.put(fff, new LinkedList<D64Report>());
			for(Iterator<File> f=F2s.iterator();f.hasNext();)
			{
				File F2=f.next();
				IMAGE_TYPE typeF2 = getImageTypeAndZipped(F2);
				if(typeF2==null)
				{
					System.err.println("**** Error reading :"+F2.getName());
					f.remove();
					continue;
				}
				int[] f2Len=new int[1];
				byte[][][] diskF2=getDisk(typeF2,F2,f2Len);
				List<FileInfo> fileData2=getDiskFiles(F2.getName(),typeF2,diskF2,f2Len[0]);
				if(fileData2==null)
				{
					System.err.println("**** Bad extension :"+F2.getName());
					f.remove();
					continue;
				}
				diskF2=null;
				for(FileInfo f2 : fileData2)
				{
					for(FileInfo f1 : fileData1)
					{
						if(f2.fileName.equals(f1.fileName))
						{
							List<D64Report> rep = report.get(f1);
							if(!Arrays.equals(f1.data, f2.data))
								rep.add(new D64Report(F2,false,f2));
							else
								rep.add(new D64Report(F2,true,f2));
						}
					}
				}
			}
			StringBuilder str=new StringBuilder("Report on "+F1.getAbsolutePath()+":\n");
			str.append("---------- Files Not Found for Diffing: \n");
			StringBuilder subStr=new StringBuilder("");
			List<FileInfo> sortedKeys = new ArrayList<FileInfo>();
			for(FileInfo key : report.keySet())
				sortedKeys.add(key);
			Collections.sort(sortedKeys,new Comparator<FileInfo>(){
				@Override
				public int compare(FileInfo o1, FileInfo o2) {
					return o1.filePath.compareTo(o2.filePath);
				}
			});
			subStr.setLength(0);
			for(FileInfo key : sortedKeys)
			{
				List<D64Report> rep = report.get(key);
				String fs1 = "  " + key.filePath+"("+key.fileType+"): "+(key.data==null?"null":Integer.toString(key.data.length));
				subStr.append(fs1).append("\n");
				if(rep.size()==0)
				{
					subStr.append("    N/A (No matches found on any target disks)").append("\n");
					continue;
				}
				for(D64Report r : rep)
				{
					String fs2 = r.compareFD.filePath+"("+r.compareFD.fileType+"): "+(r.compareFD.data==null?"null":Integer.toString(r.compareFD.data.length));
					int len=50;
					if(fs1.length()>len)
						len=fs1.length();
					int dlen=20;
					if(r.diskF.getName().length()>dlen)
						dlen=r.diskF.getName().length();
					subStr.append("    "+(r.equal?"MATCH:":"DIFF :")).append(fs1).append(spaces.substring(0,len-fs1.length())).append(fs2).append("  ("+r.diskF.getName()+")").append("\n");
				}
			}
			if(subStr.length()==0)
				str.append("NONE!\n");
			else
				str.append(subStr.toString());
			System.out.println(str.toString());
		}
	}
}
