/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi <famontesi@gmail.com>               *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU Library General Public License as       *
 *   published by the Free Software Foundation; either version 2 of the    *
 *   License, or (at your option) any later version.                       *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU Library General Public     *
 *   License along with this program; if not, write to the                 *
 *   Free Software Foundation, Inc.,                                       *
 *   59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.             *
 ***************************************************************************/

package jolie;

import java.util.Vector;
import java.io.IOException;

public class SolicitResponseProcess implements Process
{
	private Operation operation;
	private Vector< Variable > outVars, inVars;
	private Location location;

	public SolicitResponseProcess( Operation operation, Location location, Vector< Variable > outVars, Vector< Variable > inVars )
	{
		this.operation = operation;
		this.location = location;
		this.outVars = outVars;
		this.inVars = inVars;
	}
	
	public void run()
	{
		try {
			CommChannel channel = location.createCommChannel( operation.getProtocol() );
			CommMessage message = new CommMessage( operation.value(), outVars );
			channel.send( message );
			message = channel.recv();
			
			if ( message.count() == inVars.size() ) {
				int i = 0;
				for( Variable recvVar : message )
					inVars.elementAt( i++ ).assignValue( recvVar );
			} // todo -- if else throw exception?
			
			channel.close();
		} catch( IOException ioe ) {
			ioe.printStackTrace();
		}
	}
}