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
	public static void main(String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Compare v1.1 (c)2016-2017 Bo Zimmerman");
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
		File F1=new File(path);
		File F2=new File(expr);
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
		
		IMAGE_TYPE typeF1 = getImageTypeAndZipped(F1);
		if(typeF1==null)
		{
			System.err.println("Error reading :"+F1.getName());
			System.exit(-1);
		}
		int[] f1Len=new int[1];
		byte[][][] diskF1=getDisk(typeF1,F1,f1Len);
		List<FileInfo> fileData1=getDiskFiles(F1.getName(),typeF1,diskF1,f1Len[0]);
		if(fileData1==null)
		{
			System.err.println("Bad extension :"+F1.getName());
			System.exit(-1);
		}
		diskF1=null;
		
		IMAGE_TYPE typeF2 = getImageTypeAndZipped(F2);
		if(typeF2==null)
		{
			System.err.println("Error reading :"+F2.getName());
			System.exit(-1);
		}
		int[] f2Len=new int[1];
		byte[][][] diskF2=getDisk(typeF2,F2,f2Len);
		List<FileInfo> fileData2=getDiskFiles(F2.getName(),typeF2,diskF2,f2Len[0]);
		if(fileData2==null)
		{
			System.err.println("Bad extension :"+F2.getName());
			System.exit(-1);
		}
		diskF2=null;
		List<FileInfo> missingFromDisk2 = new LinkedList<FileInfo>();
		missingFromDisk2.addAll(fileData1);
		List<FileInfo[]> foundButNotEqual = new LinkedList<FileInfo[]>();
		for(FileInfo f1 : fileData1)
		{
			for(FileInfo f2 : fileData2)
			{
				if(f1.filePath.equals(f2.filePath))
				{
					missingFromDisk2.remove(f1);
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		List<FileInfo> missingFromDisk1 = new LinkedList<FileInfo>();
		missingFromDisk1.addAll(fileData2);
		for(FileInfo f2 : fileData2)
		{
			for(FileInfo f1 : fileData1)
			{
				if(f2.filePath.equals(f1.filePath))
				{
					missingFromDisk1.remove(f2);
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		List<FileInfo[]> wrongPathFromDisks = new LinkedList<FileInfo[]>();
		for(FileInfo f2 : fileData2)
		{
			for(Iterator<FileInfo> i=missingFromDisk2.iterator();i.hasNext();)
			{
				FileInfo f1=i.next();
				if(f1.fileName.equalsIgnoreCase(f2.fileName))
				{
					if(Arrays.equals(f1.data, f2.data))
					{
						wrongPathFromDisks.add(new FileInfo[]{f1,f2});
					}
				}
			}
		}
		for(FileInfo f1 : fileData1)
		{
			for(Iterator<FileInfo> i=missingFromDisk1.iterator();i.hasNext();)
			{
				FileInfo f2=i.next();
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
			for(FileInfo f : missingFromDisk1)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			perfectMatch=false;
			System.out.println("");
		}
		if(missingFromDisk2.size()>0)
		{
			System.out.println("Found in disk 1, but missing from disk 2:");
			for(FileInfo f : missingFromDisk2)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			perfectMatch=false;
			System.out.println("");
		}
		if(wrongPathFromDisks.size()>0)
		{
			System.out.println("Found in one disk, but at a different path from the other disk:");
			for(FileInfo[] f : wrongPathFromDisks)
				System.out.println("1: "+f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus 2: "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			perfectMatch=false;
			System.out.println("");
		}
		if(foundButNotEqual.size()>0)
		{
			System.out.println("Found, but with different data:");
			for(FileInfo[] f : foundButNotEqual)
				System.out.println("1: "+f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus 2: "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			perfectMatch=false;
			System.out.println("");
		}
		if(perfectMatch)
			System.out.println(fileData1.size()+" files matched perfectly on both images.");
	}
}
