package org.embl.mobie.viewer.table.saw;

import net.imglib2.roi.RealMaskRealInterval;
import net.imglib2.util.Intervals;
import org.embl.mobie.viewer.ImageStore;
import org.embl.mobie.viewer.annotation.AnnotatedRegion;
import org.embl.mobie.viewer.table.ColumnNames;
import org.embl.mobie.viewer.transform.TransformHelper;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.Table;

import java.util.List;
import java.util.function.Supplier;

public class TableSawAnnotatedRegion implements AnnotatedRegion
{
	private static final String[] idColumns = new String[]{ ColumnNames.REGION_ID };
	private final Supplier< Table > tableSupplier;
	private final int rowIndex;

	private final List< String > imageNames;
	private RealMaskRealInterval realMaskRealInterval;
	private String regionId;
	private int label;
	private final int timePoint;
	private String sourceName;

	public TableSawAnnotatedRegion(
			Supplier< Table > tableSupplier,
			int rowIndex,
			List< String > imageNames )
	{
		this.tableSupplier = tableSupplier;
		this.rowIndex = rowIndex;
		this.imageNames = imageNames;

		final Row row = tableSupplier.get().row( rowIndex );
		// fetch region properties from table row

		this.regionId = row.getObject( ColumnNames.REGION_ID ).toString();
		this.label = regionId.hashCode();
		this.timePoint = row.columnNames().contains( ColumnNames.TIMEPOINT ) ? row.getInt( ColumnNames.TIMEPOINT ) : 0;
		this.sourceName = tableSupplier.get().name();
	}

	@Override
	public int label()
	{
		return label;
	}

	@Override
	public int timePoint()
	{
		return timePoint;
	}

	@Override
	public double[] positionAsDoubleArray()
	{
		final double[] min = Intervals.minAsDoubleArray( getMask() );
		final double[] max = Intervals.maxAsDoubleArray( getMask() );
		final double[] center = new double[ min.length ];
		for ( int d = 0; d < min.length; d++ )
			center[ d ] = ( max[ d ] + min[ d ] ) / 2.0;
		return center;
	}

	@Override
	public double getDoublePosition( int d )
	{
		return positionAsDoubleArray()[ d ];
	}

	@Override
	public String uuid()
	{
		return "" + timePoint + ";" + regionId;
	}

	@Override
	public String dataSource()
	{
		return sourceName;
	}

	@Override
	public Object getValue( String feature )
	{
		return tableSupplier.get().row( rowIndex ).getObject( feature );
	}

	@Override
	public void setString( String columnName, String value )
	{
		tableSupplier.get().row( rowIndex ).setText( columnName, value );
	}

	@Override
	public String[] idColumns()
	{
		return idColumns;
	}

	@Override
	public RealMaskRealInterval getMask()
	{
		if ( realMaskRealInterval == null )
			realMaskRealInterval = TransformHelper.getUnionMask( ImageStore.getImages( imageNames ), timePoint() );

		return realMaskRealInterval;
	}

	@Override
	public String regionId()
	{
		return regionId;
	}

	@Override
	public int numDimensions()
	{
		return positionAsDoubleArray().length;
	}
}
