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

/** Skeletal implementation of MappedGlobalObject, provided for convenience.
 * 
 * @see MappedGlobalObject
 * @author Fabrizio Montesi
 *
 */
abstract public class AbstractMappedGlobalObject implements MappedGlobalObject
{
	private String id;

	public AbstractMappedGlobalObject( String id )
	{
		this.id = id;
	}
	
	public final String id()
	{
		return id;
	}

	public final void register()
	{// todo - what if the id is already registered?
		Interpreter.registerObject( id, this );
	}
}