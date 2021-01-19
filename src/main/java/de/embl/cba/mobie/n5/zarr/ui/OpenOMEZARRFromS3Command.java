package de.embl.cba.mobie.n5.zarr.ui;

import bdv.util.BdvFunctions;
import de.embl.cba.mobie.n5.zarr.N5OMEZarrImageLoader;
import de.embl.cba.mobie.n5.zarr.OMEZarrS3Reader;
import de.embl.cba.mobie.n5.zarr.OMEZarrViewer;
import mpicbg.spim.data.SpimData;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;


@Plugin(type = Command.class, menuPath = "Plugins>BigDataViewer>OME ZARR>Open OME ZARR From S3..." )
public class OpenOMEZARRFromS3Command implements Command
{
	@Parameter ( label = "S3 URL" )
	public String s3URL = "https://s3.embl.de/i2k-2020/em-raw.ome.zarr";

	@Override
	public void run()
	{
		try
		{
			SpimData spimData = OMEZarrS3Reader.readURL( s3URL );
			BdvFunctions.show( spimData );
		}
		catch ( IOException e )
		{
			e.printStackTrace();
		}

		// emAndLabelSources = BdvFunctions.show( emAndLabels, BdvOptions.options().addTo( myosinBdvSources.get( 0 ).getBdvHandle() ) );
		// Sources.showAsLabelMask( emAndLabelSources.get( 1 ) );
	}

	public static void main( String[] args ) throws IOException
	{
		// SpimData spimData = OMEZarrS3Reader.readURL( "https://s3.embl.de/i2k-2020/em-raw.ome.zarr" );
		SpimData spimData = OMEZarrS3Reader.readURL( "https://s3.embassy.ebi.ac.uk/idr/zarr/v0.1/4495402.zarr" );

		final OMEZarrViewer viewer = new OMEZarrViewer( spimData );
		viewer.show();
	}
}