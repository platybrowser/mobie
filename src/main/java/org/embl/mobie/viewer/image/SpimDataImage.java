package org.embl.mobie.viewer.image;

import bdv.SpimSource;
import bdv.VolatileSpimSource;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.Volatile;
import net.imglib2.roi.RealMaskRealInterval;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import org.embl.mobie.io.ImageDataFormat;
import org.embl.mobie.io.SpimDataOpener;
import org.embl.mobie.viewer.image.Image;
import org.embl.mobie.viewer.source.DefaultSourcePair;
import org.embl.mobie.viewer.source.SourceHelper;
import org.embl.mobie.viewer.source.SourcePair;

public class SpimDataImage< T extends NumericType< T > & RealType< T > > implements Image< T >
{
	private final ImageDataFormat imageDataFormat;
	private final String path;
	private final int setupId;
	private SourcePair< T > sp;
	private String name;

	public SpimDataImage( ImageDataFormat imageDataFormat, String path, int setupId, String name )
	{
		this.imageDataFormat = imageDataFormat;
		this.path = path;
		this.setupId = setupId;
		this.name = name;
	}

	@Override
	public SourcePair< T > getSourcePair()
	{
		if( sp == null ) open();
		return sp;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public RealMaskRealInterval getMask( )
	{
		return SourceHelper.estimateMask( getSourcePair().getSource(), 0 );
	}

	private void open()
	{
		//final long start = System.currentTimeMillis();
		final AbstractSpimData spimData = tryOpenSpimData( path, imageDataFormat );
		final SpimSource< T > s = new SpimSource<>( spimData, setupId, name );
		final VolatileSpimSource< ? extends Volatile< T > > vs = new VolatileSpimSource<>( spimData, setupId, name );
		sp = new DefaultSourcePair( s, vs );
		//IJ.log( "Opened image " + getName() + " in " + ( System.currentTimeMillis() - start ) + " ms." );
	}

	public static AbstractSpimData tryOpenSpimData( String path, ImageDataFormat imageDataFormat )
	{
		try
		{
			return new SpimDataOpener().openSpimData( path, imageDataFormat );
		}
		catch ( SpimDataException e )
		{
			e.printStackTrace();
			throw new RuntimeException( e );
		}
	}
}