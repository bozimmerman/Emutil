package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
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
public class D64Mod extends D64Base
{
	enum Action
	{
		SCRATCH, EXTRACT, INSERT, BAM, LIST
	}
	
	enum BamAction
	{
		CHECK, ALLOC, FREE
	}
	
	public List<short[]> getAllocationPattern()
	{
		List<short[]> list=new LinkedList<short[]>();
		
		return list;
	}
	
	public static void main(String[] args)
	{
		if(args.length<3)
		{
			System.out.println("D64Mod v1.0 (c)2017-2017 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Mod <image file> <action> <action arguments>");
			System.out.println("ACTIONS:");
			System.out.println("  SCRATCH <file>");
			System.out.println("  EXTRACT <file> <target path>");
			System.out.println("  INSERT <source path> <file>");
			System.out.println("  BAM CHECK");
			System.out.println("  BAM ALLOC (Checks for sectors that need bam alloc)");
			System.out.println("  BAM FREE (Checks for sectors that need bam free)");
			System.out.println("  LIST <PATH>");
			System.out.println("  LIST ALL");
			System.out.println("");
			return;
		}
		String imagePath=args[0];
		String actionStr=args[1];
		String imageFileStr = args[2];
		String expr="";
		expr=expr.trim();
		File imageF=new File(imagePath);
		if((!imageF.isFile())||(!imageF.exists())||(!imageF.canRead()))
		{
			System.err.println("image not found: "+imagePath);
			System.exit(-1);
		}
		IMAGE_TYPE imagetype = getImageType(imageF);
		if(imagetype == null)
		{
			System.err.println("File is not an image: "+imagePath);
			System.exit(-1);
		}
		Action action = Action.EXTRACT;
		BamAction bamAction = BamAction.CHECK;
		String localFileStr="";
		try
		{
			action=Action.valueOf(actionStr.toUpperCase().trim());
			switch(action)
			{
			case SCRATCH:
				break;
			case INSERT:
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				localFileStr = args[2];
				imageFileStr = args[3];
				break;
			case EXTRACT:
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				localFileStr = args[3];
				break;
			case BAM:
				try
				{
					bamAction=BamAction.valueOf(args[2].toUpperCase().trim());
				}
				catch(Exception e)
				{
					System.err.println("Invalid sub-command");
					System.exit(-1);
				}
				break;
			case LIST:
				/*
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				*/
				imageFileStr = args[2];
				break;
			}
		}
		catch(Exception e)
		{
			System.err.println("Invalid action: "+actionStr);
			System.exit(-1);
		}
		final byte[][][] diskBytes = getDisk(imagetype,imageF);
		final List<FileInfo> files = getDiskFiles(imageF, imagetype, diskBytes, imageF.length());
		FileInfo file = null;
		if(imageFileStr.length()>0)
		{
			for(FileInfo f : files)
			{
				if(f.filePath.equalsIgnoreCase(imageFileStr))
				{
					file = f;
					break;
				}
			}
		}
		if(action == Action.LIST)
		{
			if((!imageFileStr.equalsIgnoreCase("ALL")) && (file == null))
			{
				System.err.println("No such path exists in image: "+imageFileStr);
				System.exit(-1);
			}
		}
		else
		if((action == Action.INSERT) && (file != null))
		{
			// that's ok.. anything is OK for insert, really.
		}
		else
		if((action != Action.BAM) && (file == null))
		{
			System.err.println("File not found in image: "+imageFileStr);
			System.exit(-1);
		}
		LinkedList<FileInfo> fileList=new LinkedList<FileInfo>();
		if(file != null)
		{
			fileList.add(file);
			if((file.fileType == FileType.DIR)||(file.fileType == FileType.CBM))
			{
				LinkedList<FileInfo> notdone=new LinkedList<FileInfo>();
				notdone.add(file);
				while(notdone.size()>0)
				{
					FileInfo dir = notdone.removeFirst();
					for(FileInfo i : files)
					{
						if(i.parentF == dir)
						{
							if((i.fileType == FileType.DIR)||(i.fileType == FileType.CBM))
								notdone.add(i);
							fileList.add(i);
						}
					}
				}
				
			}
		}
		
		final boolean[] rewriteD64 = {false};
		switch(action)
		{
		case SCRATCH:
		{
			while(fileList.size()>0)
			{
				FileInfo f = fileList.removeLast();
				byte[] dirSector = diskBytes[file.dirLoc[0]][file.dirLoc[1]];
				dirSector[file.dirLoc[2]]=(byte)(dirSector[file.dirLoc[2]]&0x7f);
				final Set<Integer> tsSet=new HashSet<Integer>();
				for(short[] ts : f.tracksNSecs)
					tsSet.add(Integer.valueOf((ts[0] << 8) + ts[1]));
				try
				{
					D64Mod.bamPeruse(diskBytes, imagetype, imageF.length(), new BAMBack() 
					{
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short bamMask) 
						{
							
							Integer tsInt=Integer.valueOf((t << 8) + s);
							if(tsSet.contains(tsInt)&&(!set))
							{
								byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
								bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
								rewriteD64[0]=true;
							}
							return false;
						}
					});
					System.out.println("Scratched "+f.filePath);
				}
				catch(Exception e)
				{
					System.err.println(e.getMessage());
					System.exit(-1);
				}
			}
			break;
		}
		case EXTRACT:
		{
			File localFileF=new File(localFileStr);
			if((fileList.size()>1)&&(!localFileF.isDirectory()))
			{
				System.err.println(localFileStr+" needs to be a directory to extract "+fileList.size()+" files.");
				System.exit(-1);
			}
			while(fileList.size()>0)
			{
				FileInfo f = fileList.removeLast();
				try 
				{
					FileOutputStream fout;
					File outF;
					if(localFileF.isDirectory())
						outF = new File(localFileF,f.fileName);
					else
						outF = localFileF;
					fout=new FileOutputStream(outF);
					fout.write(f.data);
					fout.close();
					System.out.println(file.data.length+" bytes written to "+outF.getAbsolutePath());
				} catch (Exception e) {
					System.err.println(e.getMessage());
					System.exit(-1);
				}
			}
			break;
		}
		case BAM:
		{
			final HashSet<Integer> used = new HashSet<Integer>();
			final HashMap<String,HashSet<Integer>> qmap = new HashMap<String,HashSet<Integer>>();
			for(FileInfo f : files)
			{
				HashSet<Integer> qset=new HashSet<Integer>();
				qmap.put(f.filePath, qset);
				for(short[] s : f.tracksNSecs)
				{
					Integer x=Integer.valueOf((s[0] << 8) + s[1]);
					if(!used.contains(x))
						used.add(x);
					if(!qset.contains(x))
						qset.add(x);
				}
			}
			try
			{
				final HashSet<Integer> unbammed=new HashSet<Integer>();
				final HashSet<Integer> overbammed=new HashSet<Integer>();
				switch(bamAction)
				{
				case CHECK:
				{
					final int[] lastT={1};
					System.out.print("1  : ");
					D64Mod.bamPeruse(diskBytes, imagetype, imageF.length(), new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short bamMask) 
						{
							
							if(t != lastT[0])
							{
								lastT[0]=t;
								System.out.println("");
								final String ts=Integer.toString(t);
								System.out.print(ts+spaces.substring(0,3-ts.length())+": ");
							}
							final Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									System.out.print("0");
									unbammed.add(tsInt);
								}
								else
									System.out.print("o");
							}
							else
							{
								if(used.contains(tsInt))
									System.out.print("x");
								else
								{
									overbammed.add(tsInt);
									System.out.print("#");
								}
							}
							return false;
						}
						
					});
					System.out.println("");
					if((unbammed.size()>0)||(overbammed.size()>0))
					{
						System.err.println("Not all sectors matched BAM allocation. 0=used, but not marked in bam.  #=UNUSED, but marked used in BAM.");
						if(unbammed.size()>0)
						{
							System.err.println("Un-Bammed files:");
							Set<String> unbammedS=new HashSet<String>();
							for(Integer I : unbammed)
								for(String fs : qmap.keySet())
									if(qmap.get(fs).contains(I))
										unbammedS.add(fs);
							for(String path : unbammedS)
							{
								System.err.print(path);
								HashSet<Integer> allF=qmap.get(path);
								int numMissing=0;
								for(Integer I : allF)
									if(!unbammed.contains(I))
										numMissing++;
								if(numMissing==0)
									System.err.println(" (total)");
								else
									System.err.println(" (partial "+(int)Math.round((double)numMissing/(double)allF.size()*100.0)+"%)");
							}
						}
						System.exit(-1);
					}
					break;
				}
				case ALLOC:
				{
					System.out.print("Allocation need check...");
					D64Mod.bamPeruse(diskBytes, imagetype, imageF.length(), new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short bamMask) 
						{
							
							Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
									bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
									rewriteD64[0]=true;
									unbammed.add(tsInt);
								}
							}
							else
							{
								if(!used.contains(tsInt))
									overbammed.add(tsInt);
							}
							return false;
						}
					});
					System.out.println(unbammed.size()+" sectors allocated.");
					break;
				}
				case FREE:
				{
					System.out.print("De-Allocation need check...");
					D64Mod.bamPeruse(diskBytes, imagetype, imageF.length(), new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short bamMask) 
						{
							
							Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									unbammed.add(tsInt);
								}
							}
							else
							{
								if(!used.contains(tsInt))
								{
									byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
									bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
									rewriteD64[0]=true;
									overbammed.add(tsInt);
								}
							}
							return false;
						}
					});
					System.out.println(overbammed.size()+" sectors freed.");
					break;
				}
				}
			} catch (IOException e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			break;
		}
		case INSERT:
		{
			int x=localFileStr.lastIndexOf('.');
			File localFileF;
			FileType poss = FileType.PRG;
			if(x>=0)
			{
				String ext=localFileStr.substring(x+1);
				try
				{ 
					poss = FileType.valueOf(ext.toUpperCase().trim()); 
					localFileF=new File(localFileStr);
					if(!localFileF.exists())
						localFileF=new File(localFileStr.substring(0,x));
				} 
				catch(Exception e)
				{
					localFileF=new File(localFileStr);
				}
			}
			else
				localFileF= new File(localFileStr);
			if(!localFileF.exists())
			{
				System.err.println("File not found: "+localFileStr);
				System.exit(-1);
			}
			byte[] fileData = new byte[(int)localFileF.length()];
			try
			{
				FileInputStream fin = new FileInputStream(localFileF);
				int totalBytesRead = 0;
				while(totalBytesRead < localFileF.length())
				{
					int bytesRead = fin.read(fileData,totalBytesRead,(int)(localFileF.length()-totalBytesRead));
					if(bytesRead >=0)
						totalBytesRead+=bytesRead;
				}
				fin.close();
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			//TODO:
			break;
		}
		case LIST:
			if(imageFileStr.equalsIgnoreCase("all"))
			{
				for(FileInfo f : files)
					System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
			}
			else
			{
				for(FileInfo f : fileList)
					System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
			}
			break;
		}
		if(rewriteD64[0])
		{
			try
			{
				FileOutputStream fout = new FileOutputStream(imageF);
				for(int b1=1;b1<diskBytes.length;b1++)
				{
					for(int b2=0;b2<diskBytes[b1].length;b2++)
					{
						fout.write(diskBytes[b1][b2]);
					}
				}
				fout.close();
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
		}
		System.exit(0);
	}
}
