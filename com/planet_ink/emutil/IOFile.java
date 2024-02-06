package com.planet_ink.emutil;

import java.io.*;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import org.apache.commons.compress.archivers.zip.*;

public class IOFile
{
	final File F;
	final File parentF;
	ZipFile zF = null;
	final ZipArchiveEntry zE;

	public IOFile(final File F)
	{
		this.F = F;
		this.parentF = F.getParentFile();
		try
		{
			if(F.getName().toLowerCase().endsWith(".zip"))
				this.zF = new ZipFile(F);
		}
		catch(final IOException e)
		{
		}
		this.zE = null;
	}

	public IOFile(final File F, final String name)
	{
		this.F = new File(F, name);
		this.parentF = F;
		try
		{
			if(F.getName().toLowerCase().endsWith(".zip"))
				this.zF = new ZipFile(F);
		}
		catch(final IOException e)
		{
		}
		this.zE = null;
	}

	public IOFile(final String name)
	{
		this.F = new File(name);
		this.parentF = this.F.getParentFile();
		try
		{
			if(F.getName().toLowerCase().endsWith(".zip"))
				this.zF = new ZipFile(F);
		}
		catch(final IOException e)
		{
			e.printStackTrace();
		}
		this.zE = null;
	}

	public IOFile(final IOFile parent, final ZipFile F, final ZipArchiveEntry E)
	{
		this.zE = E;
		this.F = null;
		this.parentF = parent.getParentFile();
		this.zF = F;
	}

	public boolean isDirectory()
	{
		if(this.F != null)
		{
			if(this.zF != null)
				return true;
			return F.isDirectory();
		}
		if(this.zE != null)
			return zE.isDirectory();;
		return false;
	}

	public boolean isFile()
	{
		if(this.F != null)
		{
			if(this.zF != null)
				return false;
			return F.isFile();
		}
		if(this.zE != null)
			return !zE.isDirectory();
		return false;
	}

	public boolean exists()
	{
		if(this.F != null)
			return F.exists();
		if(this.zE != null)
			return true;
		return false;
	}

	public boolean canRead()
	{
		if(this.F != null)
			return F.canRead();
		if(this.zE != null)
			return true;
		return false;
	}

	public String getName()
	{
		if(this.F != null)
			return F.getName();
		if(this.zE != null)
			return zE.getName();
		return "";
	}

	public File getParentFile()
	{
		if(parentF != null)
			return parentF;
		return null;
	}

	public String getParent()
	{
		final File pF = getParentFile();
		if(pF == null)
			return "";
		return pF.getAbsolutePath();
	}

	public long length()
	{
		if(this.F != null)
			return this.F.length();
		if(this.zE != null)
			return zE.getSize();
		return 0;
	}

	public IOFile[] listFiles()
	{
		if(F != null)
		{
			if(zF != null)
			{
				final List<IOFile> entries = new LinkedList<IOFile>();
				for(final Enumeration<? extends ZipArchiveEntry> e = zF.getEntries(); e.hasMoreElements(); )
				{
					final ZipArchiveEntry E = e.nextElement();
					if(!E.isDirectory())
						entries.add(new IOFile(this, zF, E));
				}
				return entries.toArray(new IOFile[entries.size()]);
			}
			else
			{
				final File[] fs = F.listFiles();
				if(fs == null)
					return null;
				final IOFile[] files = new IOFile[fs.length];
				for(int i=0;i<fs.length;i++)
					files[i] = new IOFile(fs[i]);
				return files;
			}
		}
		else
		{
			return new IOFile[0];
		}
	}

	public OutputStream createOutputStream() throws IOException
	{
		if(F != null)
		{
			if(zF != null) // can't output stream to a directory
				return null;
			return new FileOutputStream(F);
		}
		else
		if(zE != null)
		{
			return null; // TODO:
		}
		else
			return null;
	}

	public InputStream createInputStream() throws IOException
	{
		if(F != null)
		{
			if(zF != null) // can't input stream to a directory
				return null;
			return new FileInputStream(F);
		}
		else
		if(zE != null)
			return zF.getInputStream(zE);
		return null;
	}

}
