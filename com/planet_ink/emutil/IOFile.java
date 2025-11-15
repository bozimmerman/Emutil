package com.planet_ink.emutil;

import java.io.*;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.CRC32;

import org.apache.commons.compress.archivers.zip.*;

public class IOFile
{
	File F;
	final IOFile parentF;
	ZipFile zF = null;
	ZipArchiveEntry zE;
	final List<Closeable> closers = new java.util.Vector<Closeable>(1);

	public IOFile(final File F)
	{
		this.F = F;
		if((F.getParentFile() == this.F)
		||(F.getParentFile() == null))
			this.parentF = this;
		else
			this.parentF = new IOFile(this.F.getParentFile(),true);
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

	public IOFile(final File F, final boolean noParent)
	{
		this.F = F;
		this.parentF = this;
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
		this.parentF = new IOFile(this.F.getParentFile(),true);
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
		this.parentF = new IOFile(this.F.getParentFile(),true);
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

	public IOFile(final IOFile parent, final String name)
	{
		if(parent.zF != null)
		{
			this.parentF = parent.parentF;
			this.F = parent.F;
			this.zE = null;
			try
			{
				this.zF = new ZipFile(parent.parentF.F);
				for(final Enumeration<? extends ZipArchiveEntry> e = zF.getEntries(); e.hasMoreElements(); )
				{
					final ZipArchiveEntry E = e.nextElement();
					if(E.getName().equals(name))
					{
						this.zE = E;
						break;
					}
				}
				if(this.zE == null)
					this.F = null;
			}
			catch(final IOException e)
			{
				e.printStackTrace();
			}
		}
		else
		if(parent.F.isDirectory())
		{
			this.F = new File(parent.F,name);
			this.parentF = new IOFile(parent.F, true);
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
		else
		{
			this.F = new File(parent.F.getParentFile(), name);
			this.parentF = new IOFile(this.F.getParentFile(),true);
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
	}

	public IOFile(final IOFile parent, final ZipFile F, final ZipArchiveEntry E)
	{
		this.zE = E;
		this.F = null;
		this.parentF = parent;
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

	public String getAbsolutePath()
	{
		if(this.F != null)
			return F.getAbsolutePath();
		if(this.zE != null)
		{
			final File pF = getParentFile();
			if(pF == null)
				return getName();
			return pF.getAbsolutePath() + File.separator + zE.getName();
		}
		return "";
	}

	public File getParentFile()
	{
		if(parentF != null)
		{
			IOFile pf = parentF;
			while(pf.F == null)
				pf = pf.parentF;
			return pf.F;
		}
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

	public void close() throws IOException
	{
		if(zF != null)
			zF.close();
		for(final Closeable c : closers)
			c.close();
		closers.clear();
	}

	public OutputStream createOutputStream() throws IOException
	{
		if(F != null)
		{
			if(zF != null) // can't output stream to a directory
				return null;
			final OutputStream o = new FileOutputStream(F);
			closers.add(o);
			return o;
		}
		else
		if(zE != null)
		{
			final File oldZipF = this.parentF.F;
			final File newZipF = new File(oldZipF.getParentFile(),oldZipF.getName()+".new");
			final ZipArchiveOutputStream zout = new ZipArchiveOutputStream(newZipF);
			for(final Enumeration<ZipArchiveEntry> e = zF.getEntries(); e.hasMoreElements(); )
			{
				final ZipArchiveEntry E = e.nextElement();
				if(!E.getName().equals(zE.getName()))
					zout.putArchiveEntry(E);
			}
			zF.close();
			final OutputStream o = new ByteArrayOutputStream() {
				@Override
				public void close() throws IOException
				{
					super.close();
					final byte[] data = this.toByteArray();
					final ZipArchiveEntry E = new ZipArchiveEntry(zE.getName());
					final CRC32 crc = new CRC32();
					crc.update(data);
					E.setSize(data.length);
					E.setTime(System.currentTimeMillis());
					E.setCrc(crc.getValue());
					zout.putArchiveEntry(E);
					zout.write(data);
					zout.closeArchiveEntry();
					zout.close();
					oldZipF.delete();
					newZipF.renameTo(oldZipF);
					parentF.zF = new ZipFile(oldZipF);
					zF = parentF.zF;
				}
			};
			closers.add(o);
			return o;
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
			final InputStream i = new FileInputStream(F);
			closers.add(i);
			return i;
		}
		else
		if(zE != null)
		{
			final InputStream i = zF.getInputStream(zE);
			closers.add(i);
			return i;
		}
		return null;
	}

}
