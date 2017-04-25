/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
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
 *                                                                         *
 *   For details about the authors of this software, see the AUTHORS file. *
 ***************************************************************************/

package jolie.behaviours;

import jolie.StatefulContext;
import jolie.runtime.Value;
import jolie.runtime.VariablePath;
import jolie.runtime.expression.Expression;

public class PostDecrementBehaviour implements Behaviour, Expression
{
	final private VariablePath path;

	public PostDecrementBehaviour( VariablePath varPath )
	{
		this.path = varPath;
	}
	
	@Override
	public Behaviour clone( TransformationReason reason )
	{
		return new PostDecrementBehaviour( (VariablePath)path.cloneExpression( reason ) );
	}
	
	@Override
	public Expression cloneExpression( TransformationReason reason )
	{
		return new PostDecrementBehaviour( (VariablePath) path.cloneExpression( reason ) );
	}
	
	@Override
	public void run(StatefulContext ctx)
	{
		if ( ctx.isKilled() )
			return;
		Value val = path.getValue( ctx );
		val.setValue( val.intValue() - 1 );
	}
	
	@Override
	public Value evaluate()
	{
		Value val = path.getValue();
		Value orig = Value.create( val.intValue() );
		val.setValue( val.intValue() - 1 );
		return orig;
	}
	
	@Override
	public boolean isKillable()
	{
		return true;
	}
}