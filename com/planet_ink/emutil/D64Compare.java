package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
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
public class D64Compare extends D64Base
{
	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Compare v"+EMUTIL_VERSION+" (c)2016-"+EMUTIL_AUTHOR);
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Compare <file1> <file2>");
			System.out.println("");
			return;
		}
		String path=null;
		String expr="";
		for(int i=0;i<args.length;i++)
		{
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
		final File F1=new File(path);
		final File F2=new File(expr);
		if((!F1.exists())||F1.isDirectory())
		{
			System.err.println("File1 not found!");
			System.exit(-1);
		}
		if((!F2.exists())||F2.isDirectory())
		{
			System.err.println("File2 not found!");
			System.exit(-1);
		}

		final IMAGE_TYPE typeF1 = getImageTypeAndGZipped(F1);
		if(typeF1==null)
		{
			System.err.println("Error reading :"+F1.getName());
			System.exit(-1);
		}
		final int[] f1Len=new int[1];
		byte[][][] diskF1=getDisk(typeF1,F1,f1Len);
		final List<FileInfo> fileData1=getFiles(F1.getName(),typeF1,diskF1,f1Len[0]);
		if(fileData1==null)
		{
			System.err.println("Bad extension :"+F1.getName());
			System.exit(-1);
		}
		diskF1=null;
		final IMAGE_TYPE typeF2 = getImageTypeAndGZipped(F2);
		if(typeF2==null)
		{
			System.err.println("Error reading :"+F2.getName());
			System.exit(-1);
		}
		final int[] f2Len=new int[1];
		byte[][][] diskF2=getDisk(typeF2,F2,f2Len);
		final List<FileInfo> fileData2=getFiles(F2.getName(),typeF2,diskF2,f2Len[0]);
		if(fileData2==null)
		{
			System.err.println("Bad extension :"+F2.getName());
			System.exit(-1);
		}
		diskF2=null;
		final List<FileInfo> missingFromDisk2 = new LinkedList<FileInfo>();
		missingFromDisk2.addAll(fileData1);
		final List<FileInfo[]> foundButNotEqual = new LinkedList<FileInfo[]>();
		for(final FileInfo f1 : fileData1)
		{
			for(final FileInfo f2 : fileData2)
			{
				if(f1.filePath.equals(f2.filePath))
				{
					missingFromDisk2.remove(f1);

					if((!Arrays.equals(f1.data, f2.data))
					&&((f1.fileType != FileType.DIR)||(f2.fileType != FileType.DIR)))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		final List<FileInfo> missingFromDisk1 = new LinkedList<FileInfo>();
		missingFromDisk1.addAll(fileData2);
		for(final FileInfo f2 : fileData2)
		{
			for(final FileInfo f1 : fileData1)
			{
				if(f2.filePath.equals(f1.filePath))
				{
					missingFromDisk1.remove(f2);
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		final List<FileInfo[]> wrongPathFromDisks = new LinkedList<FileInfo[]>();
		for(final FileInfo f2 : fileData2)
		{
			for(final Iterator<FileInfo> i=missingFromDisk2.iterator();i.hasNext();)
			{
				final FileInfo f1=i.next();
				if(f1.fileName.equalsIgnoreCase(f2.fileName))
				{
					if(Arrays.equals(f1.data, f2.data))
					{
						wrongPathFromDisks.add(new FileInfo[]{f1,f2});
					}
				}
			}
		}
		for(final FileInfo f1 : fileData1)
		{
			for(final Iterator<FileInfo> i=missingFromDisk1.iterator();i.hasNext();)
			{
				final FileInfo f2=i.next();
				if(f2.fileName.equalsIgnoreCase(f1.fileName))
				{
					if(Arrays.equals(f1.data, f2.data))
					{
						wrongPathFromDisks.add(new FileInfo[]{f1,f2});
					}
				}
			}
		}
		System.out.println("");
		boolean perfectMatch=true;
		if(missingFromDisk1.size()>0)
		{
			System.out.println("Found in disk 2, but missing from disk 1:");
			for(final FileInfo f : missingFromDisk1)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			perfectMatch=false;
			System.out.println("");
		}
		if(missingFromDisk2.size()>0)
		{
			System.out.println("Found in disk 1, but missing from disk 2:");
			for(final FileInfo f : missingFromDisk2)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			perfectMatch=false;
			System.out.println("");
		}
		if(wrongPathFromDisks.size()>0)
		{
			System.out.println("Found in one disk, but at a different path from the other disk:");
			for(final FileInfo[] f : wrongPathFromDisks)
				System.out.println("1: "+f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus 2: "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			perfectMatch=false;
			System.out.println("");
		}
		if(foundButNotEqual.size()>0)
		{
			System.out.println("Found, but with different data:");
			for(final FileInfo[] f : foundButNotEqual)
				System.out.println("1: "+f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus 2: "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			perfectMatch=false;
			System.out.println("");
		}
		if(perfectMatch)
			System.out.println(fileData1.size()+" files matched perfectly on both images.");
	}
}
