/*-
 * #%L
 * Fiji viewer for MoBIE projects
 * %%
 * Copyright (C) 2018 - 2022 EMBL
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.embl.mobie.viewer.image;

import bdv.util.Affine3DHelpers;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.Volatile;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.roi.RealMaskRealInterval;
import net.imglib2.roi.geom.GeomMasks;
import net.imglib2.type.Type;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.embl.mobie.viewer.ImageStore;
import org.embl.mobie.viewer.MoBIEHelper;
import org.embl.mobie.viewer.ThreadHelper;
import org.embl.mobie.viewer.source.MoBIEVolatileTypeMatcher;
import org.embl.mobie.viewer.source.RandomAccessibleIntervalMipmapSource;
import org.embl.mobie.viewer.source.SourcePair;
import org.embl.mobie.viewer.transform.TransformHelper;
import org.embl.mobie.viewer.transform.image.ImageGridTransformer;
import org.embl.mobie.viewer.transform.image.InitialisedMetadataImage;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class StitchedImage< T extends Type< T >, V extends Volatile< T > & Type< V > > implements Image< T >
{
	protected final T type;
	protected final String name;
	protected final List< ? extends Image< T > > images;
	protected final List< int[] > positions;
	protected final double relativeCellMargin;
	protected int[][] tileDimensions;
	protected double[] tileRealDimensions;
	protected RealMaskRealInterval mask;
	protected int numMipmapLevels;
	protected double[][] downSamplingFactors;
	protected DefaultSourcePair< T > sourcePair;
	protected V volatileType;
	protected double[][] mipmapScales;
	private long[] minPos;
	private long[] maxPos;
	private int numDimensions;
	private VoxelDimensions voxelDimensions;
	private HashMap< Integer, AffineTransform3D > levelToSourceTransform;
	private HashMap< Integer, long[] > levelToSourceDimensions;
	private HashMap< Integer, double[] > levelToTileMarginVoxelTranslation;

	public StitchedImage( List< ? extends Image< T > > images, @Nullable List< int[] > positions, String imageName, double relativeCellMargin, boolean transformImageTiles )
	{
		// Init reference image metadata
		// (if we knew all that information we would not need to load
		//  the reference image and thus safe time during initialization)
		//
		final Image< T > referenceImage = images.iterator().next();
		Source< T > referenceSource = referenceImage.getSourcePair().getSource();
		this.type = referenceSource.getType().createVariable();
		this.volatileType = ( V ) MoBIEVolatileTypeMatcher.getVolatileTypeForType( type );
		this.numMipmapLevels = referenceSource.getNumMipmapLevels();
		this.numDimensions = referenceSource.getVoxelDimensions().numDimensions();
		this.voxelDimensions = referenceSource.getVoxelDimensions();
		this.levelToSourceTransform = new HashMap<>();
		this.levelToSourceDimensions = new HashMap<>();

		for ( int level = 0; level < numMipmapLevels; level++ )
		{
			final AffineTransform3D affineTransform3D = new AffineTransform3D();
			referenceSource.getSourceTransform( 0, level, affineTransform3D );
			levelToSourceTransform.put( level, affineTransform3D );
			levelToSourceDimensions.put( level,  referenceSource.getSource( 0 ,level ).dimensionsAsLongArray() );
		}

		this.images = images;
		this.positions = positions == null ? TransformHelper.createGridPositions( images.size() ) : positions;
		this.relativeCellMargin = relativeCellMargin;
		this.name = imageName;
		// TODO: make my own VolatileTypeMatcher including AnnotationType

		setMinMaxPos();
		configureMipmapAndTileDimensions();
		setTileRealDimensions( tileDimensions[ 0 ] );
		setRealMask( tileRealDimensions );

		createSourcePair();

		if ( transformImageTiles )
			transform( images );
	}

	private double[] computeTileMarginOffset( Image< ? > image, double[] tileRealDimensions )
	{
		final RealInterval realInterval = image.getMask();

		final double[] imageRealDimensions = new double[ 3 ];
		for ( int d = 0; d < 3; d++ )
			imageRealDimensions[ d ] = ( realInterval.realMax( d ) - realInterval.realMin( d ) );

		final double[] translationOffset = new double[ 2 ];
		for ( int d = 0; d < 2; d++ )
			translationOffset[ d ] = 0.5 * ( tileRealDimensions[ d ] - imageRealDimensions[ d ] );

		return translationOffset;
	}

	// Transform all the images that are stitched to be
	// at the same location as they appear in the stitched image.
	// This is currently needed for image annotation displays
	// in order to know the location of the annotated images.
	protected void transform( List< ? extends Image< ? > > images )
	{
		final Image< ? > referenceImage = images.get( 0 );
		final double[] offset = computeTileMarginOffset( referenceImage, tileRealDimensions );

		final List< List< ? extends Image< ? > > > nestedImages = new ArrayList<>();
		for ( Image< ? > image : images )
		{
			final List< Image< ? > > imagesAtGridPosition = new ArrayList<>();

			// Only the image at the first grid position
			// (the reference image) may have been loaded.
			// Avoid loading of the other images by providing the metadata
			// of the reference image (note that for a StitchedImage all images
			// are required to have the same metadata).
			if ( image == referenceImage )
			{
				imagesAtGridPosition.add( referenceImage );
			}
			else
			{
				// The reason for doing this is not the translation,
				// but the fact that a RegionLabelImage may be build to
				// annotate those images and that would trigger loading of the
				// data.
				final InitialisedMetadataImage initialisedMetadataImage = new InitialisedMetadataImage( image, referenceImage.getMask() );
				imagesAtGridPosition.add( initialisedMetadataImage );
			}

			if ( image instanceof StitchedImage )
			{
				// Also transform the image tiles that are contained
				// in the stitched image.
				// Here, we don't need to use InitialisedMetadataImage again,
				// because those tiles are already InitialisedMetadataImages.
				final List< String > tileNames = ( ( StitchedImage< ?, ? > ) image ).getTileImages().stream().map( i -> i.getName() ).collect( Collectors.toList() );
				final Set< Image< ? > > stitchedImages = ImageStore.getImages( tileNames );
				for ( Image< ? > containedImage : stitchedImages )
				{
					if ( containedImage instanceof StitchedImage )
						throw new UnsupportedOperationException("Nested stitching of MergedGridTransformation is currently not supported.");

					imagesAtGridPosition.add( containedImage );
				}
			}

			nestedImages.add( imagesAtGridPosition );
		}

		new ImageGridTransformer().transform( nestedImages, null, positions, tileRealDimensions, false, offset );
	}


	public List< ? extends Image< T > > getTileImages()
	{
		return images;
	}

	protected void createSourcePair()
	{
		final AffineTransform3D[] mipmapTransforms = new AffineTransform3D[ mipmapScales.length ];
		for ( int level = 0; level < mipmapScales.length; ++level )
		{
			final AffineTransform3D mipmapTransform = new AffineTransform3D();
			final double[] offsets = Arrays.stream( levelToTileMarginVoxelTranslation.get( level ) ).map( d -> d - ( long ) d ).toArray();
			final double[] translations = new double[ 3 ];
			for ( int d = 0; d < 3; d++ )
			{
				// TODO there still is some jumping between the pyramids, not sure why!
				translations[ d ] = 0.5 * ( mipmapScales[ level ][ d ] - 1 ) + offsets[ d ];
			}

			mipmapTransform.set(
					mipmapScales[ level ][ 0 ], 0, 0, translations[ 0 ],
					0, mipmapScales[ level ][ 1 ], 0, translations[ 1 ],
					0, 0, mipmapScales[ level ][ 2 ], translations[ 2 ] );
			mipmapTransform.preConcatenate( levelToSourceTransform.get( 0 ) );
			mipmapTransforms[ level ] = mipmapTransform;
		}

		final RandomAccessibleSupplier randomAccessibleSupplier = new RandomAccessibleSupplier();

		// non-volatile
		//
		final List< RandomAccessibleInterval< T > > mipmapRAIs = createStitchedRAIs( randomAccessibleSupplier );

		final RandomAccessibleIntervalMipmapSource< T > source = new RandomAccessibleIntervalMipmapSource<>(
				mipmapRAIs,
				type,
				voxelDimensions,
				name,
				mipmapTransforms );

		// volatile
		//
		final List< RandomAccessibleInterval< V > > volatileMipMapRAIs = createVolatileStitchedRAIs( randomAccessibleSupplier );

		final RandomAccessibleIntervalMipmapSource< V > volatileSource = new RandomAccessibleIntervalMipmapSource<>(
				volatileMipMapRAIs,
				volatileType,
				voxelDimensions,
				name,
				mipmapTransforms );

		sourcePair = new DefaultSourcePair<>( source, volatileSource );
	}

	protected List< RandomAccessibleInterval< V > > createVolatileStitchedRAIs( RandomAccessibleSupplier randomAccessibleSupplier )
	{
		final List< RandomAccessibleInterval< V >> stitchedMipMapRAIs = new ArrayList<>();

		for ( int level = 0; level < numMipmapLevels; level++ )
		{
			final V background = volatileType.createVariable();
			background.setValid( true );
			final FunctionRandomAccessible< V > randomAccessible = new FunctionRandomAccessible( 3, new VolatileBiConsumerSupplier( randomAccessibleSupplier, level, background ), () -> volatileType.createVariable() );
			final IntervalView< V > rai = Views.interval( randomAccessible, getInterval( level ) );
			stitchedMipMapRAIs.add( rai );
		}

		return stitchedMipMapRAIs;
	}

	class VolatileBiConsumerSupplier implements Supplier< BiConsumer< Localizable, V > >
	{
		private final RandomAccessibleSupplier randomAccessibleSupplier;
		private final int level;
		private int[] tileDimension;
		private final V background;

		public VolatileBiConsumerSupplier( RandomAccessibleSupplier randomAccessibleSupplier, int level, V background )
		{
			this.randomAccessibleSupplier = randomAccessibleSupplier;
			this.level = level;
			this.tileDimension = tileDimensions[ level ];
			this.background = background;
		}

		@Override
		// This essentially returns a randomAccess.
		// Now, since this is a stitched image, it needs
		// to stitch the random accesses of the tiles.
		// Thus, it internally needs to hold onto several random accesses.
		public synchronized BiConsumer< Localizable, V > get()
		{
			return new VolatileBiConsumerImplementation();
		}

		class VolatileBiConsumerImplementation implements BiConsumer< Localizable, V >
		{
			public VolatileBiConsumerImplementation()
			{
			}

			@Override
			public void accept( Localizable localizable, V volatileOutput )
			{
				int x = localizable.getIntPosition( 0 );
				int y = localizable.getIntPosition( 1 );
				final int xTileIndex = x / tileDimension[ 0 ];
				final int yTileIndex = y / tileDimension[ 1 ];

				if ( ! randomAccessibleSupplier.exists( level, xTileIndex, yTileIndex ) )
				{
					volatileOutput.set( background.copy() );
					volatileOutput.setValid( true );
					return;
				}

				final Status status = randomAccessibleSupplier.status( level, xTileIndex, yTileIndex );
				if ( status.equals( Status.Closed ) )
				{
					ThreadHelper.ioExecutorService.submit( () -> randomAccessibleSupplier.open( level, xTileIndex, yTileIndex ) );
					volatileOutput.setValid( false );
				}
				else if ( status.equals( Status.Opening ) )
				{
					volatileOutput.setValid( false );
				}
				else if ( status.equals( Status.Open ) )
				{
					x = x - xTileIndex * tileDimension[ 0 ];
					y = y - yTileIndex * tileDimension[ 1 ];
					final int z = localizable.getIntPosition( 2 );
					final V volatileType = randomAccessibleSupplier.getVolatileRandomAccessible( level, xTileIndex, yTileIndex ).getAt( x, y, z );
					volatileOutput.set( volatileType );
				}
			}
		}
	}

	protected List< RandomAccessibleInterval< T > > createStitchedRAIs( RandomAccessibleSupplier randomAccessibleSupplier )
	{
		final List< RandomAccessibleInterval< T >> stitchedRAIs = new ArrayList<>();
		for ( int l = 0; l < numMipmapLevels; l++ )
		{
			final int[] cellDimension = tileDimensions[ l ];
			final int level = l;
			BiConsumer< Localizable, T > biConsumer = ( location, value ) ->
			{
				int x = location.getIntPosition( 0 );
				int y = location.getIntPosition( 1 );
				final int xTileIndex = x / cellDimension[ 0 ];
				final int yTileIndex = y / cellDimension[ 1 ];
				x = x - xTileIndex * cellDimension [ 0 ];
				y = y - yTileIndex * cellDimension [ 1 ];

				if( ! randomAccessibleSupplier.exists( level, xTileIndex, yTileIndex  ) )
				{
					// background
					value.set( type.createVariable() );
				}

				// this is not very efficient, but right now this mainly
				// is needed to fetch single pixel values when
				// a user clicks on an image
				// TODO MUST For rendering screenshots it will be more
				//  efficient to implement this in the same way as for the
				//  volatile version (see below).
				randomAccessibleSupplier.open( level, xTileIndex, yTileIndex );
				final T t = randomAccessibleSupplier.getRandomAccessible( level, xTileIndex, yTileIndex ).randomAccess().setPositionAndGet( x, y, location.getIntPosition( 2 ) );
				value.set( t );
			};

			final FunctionRandomAccessible< T > randomAccessible = new FunctionRandomAccessible( 3, biConsumer, () -> type.createVariable() );
			final IntervalView< T > rai = Views.interval( randomAccessible, getInterval( level ) );

			stitchedRAIs.add( rai );
		}

		return stitchedRAIs;
	}

	protected void setTileRealDimensions( int[] cellDimensions )
	{
		tileRealDimensions = new double[ 3 ];
		for ( int d = 0; d < 2; d++ )
			tileRealDimensions[ d ] = cellDimensions[ d ] * Affine3DHelpers.extractScale( levelToSourceTransform.get( 0 ), d );
	}

	protected void configureMipmapAndTileDimensions( )
	{
		final double[][] voxelSizes = new double[ numMipmapLevels ][ numDimensions ];
		for ( int level = 0; level < numMipmapLevels; level++ )
		{
			final AffineTransform3D affineTransform3D = levelToSourceTransform.get( level );
			for ( int d = 0; d < numDimensions; d++ )
				voxelSizes[ level ][ d ] =
					Math.sqrt(
						affineTransform3D.get( 0, d ) * affineTransform3D.get( 0, d ) +
						affineTransform3D.get( 1, d ) * affineTransform3D.get( 1, d ) +	    				affineTransform3D.get( 2, d ) * affineTransform3D.get( 2, d )
						);
		}

		downSamplingFactors = new double[ numMipmapLevels ][ numDimensions ];
		mipmapScales = new double[ numMipmapLevels ][ numDimensions ];
		for ( int d = 0; d < numDimensions; d++ )
		{
			downSamplingFactors[ 0 ][ d ] = 1.0;
			mipmapScales[ 0 ][ d ] = 1.0 / downSamplingFactors[ 0 ][ d ];
		}

		for ( int level = 1; level < numMipmapLevels; level++ )
			for ( int d = 0; d < numDimensions; d++ )
			{
				downSamplingFactors[ level ][ d ] = voxelSizes[ level ][ d ] / voxelSizes[ level - 1 ][ d ];
				mipmapScales[ level ][ d ] = voxelSizes[ level ][ d ] / voxelSizes[ 0 ][ d ];
			}

		final double[] downSamplingFactorProducts = new double[ numDimensions ];
		Arrays.fill( downSamplingFactorProducts, 1.0D );

		for ( int level = 1; level < numMipmapLevels; level++ )
			for ( int d = 0; d < numDimensions; d++ )
				downSamplingFactorProducts[ d ] *= downSamplingFactors[ level ][ d ];

		tileDimensions = new int[ numMipmapLevels ][ numDimensions ];

		// Adapt the cell dimensions such that they are divisible
		// by all relative changes of the resolutions between the different levels.
		// If we don't do this there are jumps of the images when zooming in and out;
		// i.e. the different resolution levels are rendered at slightly offset
		// positions.
		tileDimensions[ 0 ] = MoBIEHelper.asInts( levelToSourceDimensions.get( 0 ) );
		for ( int d = 0; d < 2; d++ )
		{
			tileDimensions[ 0 ][ d ] *= ( 1 + 2.0 * relativeCellMargin );
			tileDimensions[ 0 ][ d ] = (int) ( downSamplingFactorProducts[ d ] * Math.ceil( tileDimensions[ 0 ][ d ] / downSamplingFactorProducts[ d ] ) );
		}

		for ( int level = 1; level < numMipmapLevels; level++ )
			for ( int d = 0; d < numDimensions; d++ )
				tileDimensions[ level ][ d ] = (int) ( tileDimensions[ level - 1 ][ d ] / downSamplingFactors[ level ][ d ] );

		this.levelToTileMarginVoxelTranslation = new HashMap<>();
		for ( int level = 0; level < numMipmapLevels; level++ )
		{
			final double[] tileMarginTranslation = computeTileMarginTranslation( tileDimensions[ level ], levelToSourceDimensions.get( level ) );
			levelToTileMarginVoxelTranslation.put( level, tileMarginTranslation );
		}
	}

	protected FinalInterval getInterval( int level )
	{
		final long[] min = new long[ 3 ];
		final long[] max = new long[ 3 ];
		for ( int d = 0; d < 2; d++ )
		{
			min[ d ] = minPos[ d ] * tileDimensions[ level ][ d ];
			max[ d ] = ( maxPos[ d ] + 1 ) * tileDimensions[ level ][ d ];
			if ( minPos[ 0 ] > 1 )
			{
				int a = 1;
			}
		}
		return new FinalInterval( min, max );
	}

	protected void setRealMask( double[] tileRealDimensions )
	{
		final double[] min = new double[ 3 ];
		final double[] max = new double[ 3 ];

		for ( int d = 0; d < 2; d++ )
		{
			min[ d ] = minPos[ d ] * tileRealDimensions[ d ];
			max[ d ] = ( maxPos[ d ] + 1 ) * tileRealDimensions[ d ];
		}

		mask = GeomMasks.closedBox( min, max );
	}

	private void setMinMaxPos()
	{
		minPos = new long[ 3 ];
		maxPos = new long[ 3 ];
		for ( int d = 0; d < 2; d++ )
		{
			final int finalD = d;
			minPos[ d ] = positions.stream().mapToInt( pos -> pos[ finalD ] ).min().orElseThrow( NoSuchElementException::new );
			maxPos[ d ] = positions.stream().mapToInt( pos -> pos[ finalD ] ).max().orElseThrow( NoSuchElementException::new );
		}
	}

	protected static double[] computeTileMarginTranslation( int[] tileDimensions, long[] dataDimensions )
	{
		final double[] translation = new double[ 3 ];
		for ( int d = 0; d < 2; d++ )
			translation[ d ] = ( tileDimensions[ d ] - dataDimensions[ d ] ) / 2.0;
		return translation;
	}

	@Override
	public SourcePair< T > getSourcePair()
	{
		return sourcePair;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public RealMaskRealInterval getMask()
	{
		return mask;
	}

	enum Status // for RandomAccessSupplier
	{
		Closed,
		Opening,
		Open;
	}

	class RandomAccessibleSupplier
	{
		protected Map< String, RandomAccessible< T > > keyToRandomAccessible;
		protected Map< String, RandomAccessible< V > > keyToVolatileRandomAccessible;
		protected Map< String, Status > keyToStatus;
		protected Map< String, Image< T > > keyToImage;

		public RandomAccessibleSupplier( )
		{
			keyToRandomAccessible = new ConcurrentHashMap<>();
			keyToVolatileRandomAccessible = new ConcurrentHashMap<>();
			keyToStatus = new ConcurrentHashMap<>();
			keyToImage = new ConcurrentHashMap<>();

			for ( int gridIndex = 0; gridIndex < positions.size(); gridIndex++ )
			{
				final int[] position = positions.get( gridIndex );
				keyToImage.put( getKey( position[ 0 ], position[ 1 ] ), images.get( gridIndex ) );

				for ( int level = 0; level < numMipmapLevels; level++ )
					keyToStatus.put( getKey( level, position[ 0 ], position[ 1 ] ), Status.Closed );
			}
		}

		public RandomAccessible< T > getRandomAccessible( int level, int xTileIndex, int yTileIndex )
		{
			return keyToRandomAccessible.get( getKey( level, xTileIndex, yTileIndex ) );
		}

		public RandomAccessible< V > getVolatileRandomAccessible( int level, int xTileIndex, int yTileIndex )
		{
			return keyToVolatileRandomAccessible.get( getKey( level, xTileIndex, yTileIndex ) );
		}

		private String getKey( int level, long x, long y )
		{
			return level + "-" + x + "-" + y;
		}

		public Status status( int level, int xTileIndex, int yTileIndex )
		{
			return keyToStatus.get( getKey( level, xTileIndex, yTileIndex ) );
		}

		public boolean exists( int level, int xTileIndex, int yTileIndex )
		{
			return keyToStatus.containsKey( getKey( level, xTileIndex, yTileIndex ) );
		}

		public void open( int level, int xTileIndex, int yTileIndex )
		{
			final String key = getKey( level, xTileIndex, yTileIndex );

			if ( keyToStatus.get( key ).equals( Status.Open )
				|| keyToStatus.get( key ).equals( Status.Opening ) )
				return;

			keyToStatus.put( key, Status.Opening );

			int t = 0; // TODO

			// Open the source
			//
			final Image< T > image = keyToImage.get( getKey( xTileIndex, yTileIndex ) );
			final RandomAccessibleInterval< T > rai = Views.zeroMin( image.getSourcePair().getSource().getSource( t, level ) );
			final RandomAccessibleInterval< ? extends Volatile< T > > vRai = Views.zeroMin(  image.getSourcePair().getVolatileSource().getSource( t, level ) );

			// Extend bounds to be able to
			// accommodate grid margin
			//
			RandomAccessible< T > randomAccessible = new ExtendedRandomAccessibleInterval( rai, new OutOfBoundsConstantValueFactory<>( type.createVariable() ) );
			RandomAccessible< V > vRandomAccessible = new ExtendedRandomAccessibleInterval( vRai, new OutOfBoundsConstantValueFactory<>( volatileType.createVariable() ) );

			// shift to create a margin
			//
			final long[] translation = Arrays.stream( levelToTileMarginVoxelTranslation.get( level ) ).mapToLong( d -> ( long ) d ).toArray();
			final RandomAccessible< T > translateRa = Views.translate( randomAccessible, translation );
			final RandomAccessible< V > translateVRa = Views.translate( vRandomAccessible, translation );

			// Make sure the random access is really ready to go
			// (i.e. all metadata are fetched).
			// This is important to avoid any blocking in BDV.
			//
			translateRa.randomAccess().get();
			translateVRa.randomAccess().get();

			// register
			//
			keyToRandomAccessible.put( key, translateRa );
			keyToVolatileRandomAccessible.put( key, translateVRa );
			keyToStatus.put( key, Status.Open );
		}

		private String getKey( int xTileIndex, int yTileIndex )
		{
			return xTileIndex + "-" + yTileIndex;
		}
	}
}
