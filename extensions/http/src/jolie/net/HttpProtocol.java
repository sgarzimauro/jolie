/***************************************************************************
 *   Copyright (C) by Fabrizio Montesi                                     *
 *   Copyright (C) by Mauro Silvagni                                       *
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


package jolie.net;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.RPC;
import com.google.gwt.user.server.rpc.RPCRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jolie.Constants;
import jolie.Interpreter;
import jolie.net.http.HttpMessage;
import jolie.net.http.HttpParser;
import jolie.net.http.JolieGWTConverter;
import jolie.net.http.MultiPartFormDataParser;
import jolie.runtime.ByteArray;
import jolie.runtime.InputOperation;
import jolie.runtime.InvalidIdException;
import jolie.runtime.Value;
import jolie.runtime.ValueVector;
import jolie.runtime.VariablePath;

import joliex.gwt.client.JolieService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


public class HttpProtocol extends CommProtocol
{
	private String inputId = null;
	final private TransformerFactory transformerFactory;
	final private Transformer transformer;
	final private DocumentBuilderFactory docBuilderFactory;
	final private DocumentBuilder docBuilder;
	final private URI uri;
	private boolean received = false;
	
	final public static String CRLF = new String( new char[] { 13, 10 } );
	
	public HttpProtocol( VariablePath configurationPath, URI uri )
		throws ParserConfigurationException, TransformerConfigurationException
	{
		super( configurationPath );
		this.uri = uri;
		docBuilderFactory = DocumentBuilderFactory.newInstance();
		docBuilderFactory.setNamespaceAware( true );
		docBuilder = docBuilderFactory.newDocumentBuilder();
		transformerFactory = TransformerFactory.newInstance();
		transformer = transformerFactory.newTransformer();
	}
		
	private HttpProtocol(
			VariablePath configurationPath,
			URI uri,
			DocumentBuilderFactory docBuilderFactory,
			DocumentBuilder docBuilder,
			TransformerFactory transformerFactory,
			Transformer transformer
		)
	{
		super( configurationPath );
		this.uri = uri;
		this.docBuilderFactory = docBuilderFactory;
		this.docBuilder = docBuilder;
		this.transformerFactory = transformerFactory;
		this.transformer = transformer;
	}
	
	public HttpProtocol clone()
	{
		HttpProtocol ret =
				new HttpProtocol(
					configurationPath(),
					uri,
					docBuilderFactory,
					docBuilder,
					transformerFactory,
					transformer
				);
		return ret;
	}
	
	private static Map< String, ValueVector > getAttributesOrNull( Value value )
	{
		Map< String, ValueVector > ret = null;
		ValueVector vec = value.children().get( Constants.Predefined.ATTRIBUTES.token().content() );
		if ( vec != null && vec.size() > 0 )
			ret = vec.first().children();
		
		if ( ret == null )
			ret = new HashMap< String, ValueVector >();
		
		return ret;
	}
	
	private static Value getAttribute( Value value, String attrName )
	{
		return value.getChildren( Constants.Predefined.ATTRIBUTES.token().content() ).first()
					.getChildren( attrName ).first();
	}
	
	private void valueToDocument(
			Value value,
			Node node,
			Document doc
			)
		throws SOAPException
	{
		Element currentElement;

		for( Entry< String, ValueVector > entry : value.children().entrySet() ) {
			if ( !entry.getKey().startsWith( "@" ) ) {
				for( Value val : entry.getValue() ) {
					currentElement = doc.createElement( entry.getKey() );
					node.appendChild( currentElement );
					Map< String, ValueVector > attrs = getAttributesOrNull( val );
					if ( attrs != null ) {
						for( Entry< String, ValueVector > attrEntry : attrs.entrySet() ) {
							currentElement.setAttribute(
								attrEntry.getKey(),
								attrEntry.getValue().first().strValue()
								);
						}
					}
					currentElement.appendChild( doc.createTextNode( val.strValue() ) );
					valueToDocument( val, currentElement, doc );
				}
			}
		}
	}
	
	private final static String BOUNDARY = "----Jol13H77p$$Bound4r1$$";
	
	private static String getCookieString( CommMessage message, String hostname )
	{
		ValueVector cookieVec = message.value().getChildren( Constants.Predefined.COOKIES.token().content() );
		String cookieString = "";
		String domain;
		// TODO check for cookie expiration
		for( Value v : cookieVec ) {
			domain = v.getChildren( "domain" ).first().strValue();
			if ( domain.isEmpty() ||
					(!domain.isEmpty() && hostname.endsWith( domain )) ) {
				cookieString +=
							v.getChildren( "name" ).first().strValue() + "=" +
							v.getChildren( "value" ).first().strValue() + "; ";
			}
		}
		return ( cookieString.isEmpty() ? "" : "Cookie: " + cookieString + CRLF );
	}
	
	private static String getSetCookieString( CommMessage message )
	{
		ValueVector cookieVec = message.value().getChildren( Constants.Predefined.COOKIES.token().content() );
		String ret = "";
		// TODO check for cookie expiration
		for( Value v : cookieVec ) {
			ret += "Set-Cookie: " +
					v.getFirstChild( "name" ).strValue() + "=" +
					v.getFirstChild( "value" ).strValue() + "; " +
					"expires=" + v.getFirstChild( "expires" ).strValue() + "; " +
					"path=" + v.getFirstChild( "path" ).strValue() + "; " +
					"domain=" + v.getFirstChild( "domain" ).strValue() +
					( (v.getFirstChild( "secure" ).intValue() > 0) ? "; secure" : "" ) +
					CRLF;
		}
		return ret;
	}
	
	private String requestFormat = null;
	
	private static String parseAlias( String alias, Value value )
	{
		StringBuilder result = new StringBuilder( alias );
		Matcher m = Pattern.compile( "%\\{[^\\}]*\\}" ).matcher( alias );
		while( m.find() ) {
			result.replace(
				m.start(), m.end(),
				value.getFirstChild( alias.substring( m.start()+2, m.end()-1 ) ).strValue()
			);
		}
		return result.toString();
	}
	
	public void send( OutputStream ostream, CommMessage message )
		throws IOException
	{
		try {
			String charset = "", charsetParam;
			charsetParam = message.value().getFirstChild( jolie.Constants.Predefined.CHARSET.token().content() ).strValue();
			if ( charsetParam.isEmpty() ) {
				charsetParam = getParameterVector( "charset" ).first().strValue();
			}
			if ( !charsetParam.isEmpty() ) {
				charset = "; charset=" + charsetParam.toLowerCase();
			}
			String contentString = "";
			String contentType = "text/plain";
			String queryString = "";
			ByteArray binaryContent = null;
			
			String format = null;
			if ( received && requestFormat != null ) {
				format = requestFormat;
				requestFormat = null;
			} else {
				format = message.value().getFirstChild( jolie.Constants.Predefined.FORMAT.token().content() ).strValue();
				if ( format.isEmpty() ) {
					format = getParameterVector( "format" ).first().strValue();
				}
			}

			if ( format.isEmpty() || format.equals( "xml" ) ) {
				Document doc = docBuilder.newDocument();
				Element root = doc.createElement( message.operationName() + (( received ) ? "Response" : "") );
				doc.appendChild( root );
				valueToDocument( message.value(), root, doc );

				Source src = new DOMSource( doc );
				ByteArrayOutputStream tmpStream = new ByteArrayOutputStream();
				Result dest = new StreamResult( tmpStream );
				transformer.transform( src, dest );

				contentString = new String( tmpStream.toByteArray() );

				contentType = "text/xml";
			} else if ( format.equals( "binary" ) ) {
				if ( message.value().isByteArray() ) {
					binaryContent = (ByteArray)message.value().valueObject();
				}
				contentType = "application/octet-stream";
			} else if ( format.equals( "html" ) ) {
				if ( !charsetParam.isEmpty() ) {
					binaryContent = new ByteArray( message.value().strValue().getBytes( charsetParam ) );
				} else {
					contentString = message.value().strValue();
				}
				contentType = "text/html";
			} else if ( format.equals( "multipart/form-data" ) ) {
				contentType = "multipart/form-data; boundary=" + BOUNDARY;
				for( Entry< String, ValueVector > entry : message.value().children().entrySet() ) {
					if ( !entry.getKey().startsWith( "@" ) ) {
						contentString += "--" + BOUNDARY + CRLF;
						contentString += "Content-Disposition: form-data; name=\"" + entry.getKey() + '\"' + CRLF + CRLF;
						contentString += entry.getValue().first().strValue() + CRLF;
					}
				}
				contentString += "--" + BOUNDARY + "--";
			} else if ( format.equals( "x-www-form-urlencoded" ) ) {
				contentType = "x-www-form-urlencoded";
				Iterator< Entry< String, ValueVector > > it =
					message.value().children().entrySet().iterator();
				Entry< String, ValueVector > entry;
				while( it.hasNext() ) {
					entry = it.next();
					contentString += entry.getKey() + "=" + URLEncoder.encode( entry.getValue().first().strValue(), "UTF-8" );
					if ( it.hasNext() ) {
						contentString += "&";
					}
				}
			} else if ( format.equals( "text/x-gwt-rpc" ) ) {
				try {
					if ( message.isFault() ) {
						contentString +=
							RPC.encodeResponseForFailure( JolieService.class.getMethods()[0], JolieGWTConverter.jolieToGwtFault( message.fault() ) );
					} else {
						joliex.gwt.client.Value v = new joliex.gwt.client.Value();
						JolieGWTConverter.jolieToGwtValue( message.value(), v );
						contentString +=
							RPC.encodeResponseForSuccess( JolieService.class.getMethods()[0], v );
					}
				} catch( SerializationException e ) {
					e.printStackTrace();
				}
			}
			
			String messageString = new String();
			
			if ( received ) {
				// We're responding to a request
				String redirect = message.value().getFirstChild( Constants.Predefined.REDIRECT.token().content() ).strValue();
				if ( redirect.isEmpty() ) {
					messageString += "HTTP/1.1 200 OK" + CRLF;
				} else {
					messageString += "HTTP/1.1 303 See Other" + CRLF;
					messageString += "Location: " + redirect + CRLF;
				}
				messageString += getSetCookieString( message );
				received = false;
			} else {
				// We're sending a notification or a solicit
				String path = new String();
				if ( uri.getPath().length() < 1 || uri.getPath().charAt( 0 ) != '/' )
					path += "/";
				path += uri.getPath();
				if ( path.endsWith( "/" ) == false )
					path += "/";
				String opName = message.operationName();
				ValueVector vec;
				String alias = "";
				if (
					(vec=getParameterVector( "aliases" ).first().children().get( opName )) == null
					) {
					path += opName;
				} else {
					alias = vec.first().strValue();
					path += parseAlias( alias , message.value() );
				}
				
				if ( format.equals( "rest" ) ) {
					StringBuilder querySB = new StringBuilder();
					querySB.append( message.value().strValue() );
					if ( message.value().children().size() > 0 ) {
						querySB.append( '?' );
						String key;
						for( Entry< String, ValueVector > entry : message.value().children().entrySet() ) {
							key = entry.getKey();
							vec = entry.getValue();
							if ( !key.startsWith( "@" ) && !alias.contains( "%{" + key + "}" ) ) {
								for( Value v : vec ) {
									querySB.append( key + "=" + URLEncoder.encode( v.strValue(),"UTF-8" ) + "&" );
								}
							}
						}
						queryString = querySB.substring( 0, querySB.length() - 1 );
					}
				}
				
				String method = "GET";
				if ( getParameterVector( "method" ).first().strValue().length() > 0 ) {
					method = getParameterVector( "method" ).first().strValue().toUpperCase();
				}
				
				messageString += method + " " + path + queryString + " HTTP/1.1" + CRLF;
				messageString += "Host: " + uri.getHost() + CRLF;
				
				messageString += getCookieString( message, uri.getHost() );
				
				if ( message.value().hasChildren( jolie.Constants.Predefined.HTTP_BASIC_AUTHENTICATION.token().content() ) ) {
					Value v = message.value().getFirstChild( jolie.Constants.Predefined.HTTP_BASIC_AUTHENTICATION.token().content() );
					//String realm = v.getFirstChild( "realm" ).strValue();
					String userpass =
						v.getFirstChild( "userid" ).strValue() + ":" +
						v.getFirstChild( "password" ).strValue();
					sun.misc.BASE64Encoder encoder = new sun.misc.BASE64Encoder();
					userpass = encoder.encode( userpass.getBytes() );
					messageString += "Authorization: Basic " + userpass + CRLF;
				}
			}
			
			if ( getParameterVector( "keepAlive" ).first().intValue() != 1 ) {
				channel.setToBeClosed( true );
				messageString += "Connection: close" + CRLF;
			}
						
			String t = message.value().getFirstChild( jolie.Constants.Predefined.CONTENT_TYPE.token().content() ).strValue();
			if ( !t.isEmpty() ) {
				contentType = t;
			}
			messageString += "Content-Type: " + contentType + charset + CRLF;

			String encoding = message.value().getFirstChild( jolie.Constants.Predefined.CONTENT_TRANSFER_ENCODING.token().content() ).strValue();
			if ( !encoding.isEmpty() ) {
				messageString += "Content-Transfer-Encoding: " + encoding + CRLF;
			}
			
			messageString += "Content-Length: " +
					( ( binaryContent == null ) ? contentString.length() : binaryContent.size() ) +
					CRLF;
			
			//messageString += "Content-Encoding: none" + CRLF;
			
			if ( getParameterVector( "debug" ).first().intValue() > 0 ) {
				String debugStr = messageString;
				if ( getParameterVector( "debug" ).first().getFirstChild( "showContent" ).intValue() > 0 ) {
					debugStr = messageString + CRLF +
						( ( binaryContent == null ) ? contentString : binaryContent.toString() ) + CRLF;
				}
				Interpreter.getInstance().logger().info( "[HTTP debug] Sending:\n" + debugStr ); 
			}
			
			messageString += CRLF;
			
			inputId = message.operationName();
			
			Writer writer = new OutputStreamWriter( ostream );
			if ( !contentType.startsWith( "image/" ) ) {
				writer.write( messageString );
			}
			if ( binaryContent == null ) {
				writer.write( contentString );
			} else {
				ostream.write( binaryContent.getBytes() );
			}
			writer.write( CRLF );
			writer.flush();
		} catch( SOAPException se ) {
			throw new IOException( se );
		} catch( TransformerException te ) {
			throw new IOException( te );
		}
	}
	
	private static void elementsToSubValues( Value value, NodeList list )
	{
		Node node;
		Value childValue;
		for( int i = 0; i < list.getLength(); i++ ) {
			node = list.item( i );
			switch( node.getNodeType() ) {
			case Node.ATTRIBUTE_NODE:
				getAttribute( value, node.getNodeName() ).setValue( node.getNodeValue() );
				break;
			case Node.ELEMENT_NODE:
				childValue = value.getNewChild( node.getLocalName() );
				elementsToSubValues( childValue, node.getChildNodes() ); 
				break;
			case Node.TEXT_NODE:
				value.setValue( node.getNodeValue() );
				break;
			}
		}
	}
	
	private void parseXML( HttpMessage message, Value value )
		throws IOException
	{
		try {
			if ( message.size() > 0 ) {
				DocumentBuilder builder = docBuilderFactory.newDocumentBuilder();
				InputSource src = new InputSource( new ByteArrayInputStream( message.content() ) );
				//InputSource src = new InputSource( new StringReader(new String( message.content() ).trim()) );

				Document doc = builder.parse( src );

				elementsToSubValues(
							value,
							doc.getFirstChild().getChildNodes()
						);
			}
		} catch( ParserConfigurationException pce ) {
			throw new IOException( pce );
		} catch( SAXException saxe ) {
			throw new IOException( saxe );
		}
	}
	
	private static void parseForm( HttpMessage message, Value value )
		throws IOException
	{
		String line = new String( message.content(), "UTF8" );
		String[] s, pair;
		s = line.split( "&" );
		for( int i = 0; i < s.length; i++ ) {
			pair = s[i].split( "=", 2 );
			value.getChildren( pair[0] ).first().setValue( pair[1] );
		}		
	}
	
	private static void parseMultiPartFormData( HttpMessage message, Value value )
		throws IOException
	{
		MultiPartFormDataParser parser = new MultiPartFormDataParser( message, value );
		parser.parse();
	}
	
	private static String parseGWTRPC( HttpMessage message, Value value )
		throws IOException
	{
		RPCRequest request = RPC.decodeRequest( new String( message.content(), "UTF8" ) );
		String operationName = (String)request.getParameters()[0];
		joliex.gwt.client.Value requestValue = (joliex.gwt.client.Value)request.getParameters()[1];
		JolieGWTConverter.gwtToJolieValue( requestValue, value );
		return operationName;
	}
	
	private static void checkForSetCookie( HttpMessage message, Value value )
	{
		ValueVector cookieVec = value.getChildren( Constants.Predefined.COOKIES.token().content() );
		Value currValue;
		for( HttpMessage.Cookie cookie : message.setCookies() ) {
			currValue = Value.create();
			currValue.getNewChild( "expires" ).setValue( cookie.expirationDate() );
			currValue.getNewChild( "path" ).setValue( cookie.path() );
			currValue.getNewChild( "name" ).setValue( cookie.name() );
			currValue.getNewChild( "value" ).setValue( cookie.value() );
			currValue.getNewChild( "domain" ).setValue( cookie.domain() );
			currValue.getNewChild( "secure" ).setValue( (cookie.secure() ? 1 : 0) );
			cookieVec.add( currValue );
		}
	}
	
	private static void checkForCookies( HttpMessage message, Value value )
	{
		ValueVector cookieVec = value.getChildren( Constants.Predefined.COOKIES.token().content() );
		Value v;
		for( Entry< String, String > entry : message.cookies().entrySet() ) {
			v = Value.create();
			v.getNewChild( "name" ).setValue( entry.getKey() );
			v.getNewChild( "value" ).setValue( entry.getValue() );
			cookieVec.add( v );
		}
	}
	
	private static void parseQueryString( HttpMessage message, Value value )
	{
		if ( message.requestPath() != null ) {
			try {
				Value qsValue = value.getFirstChild( Constants.Predefined.QUERY_STRING.token().content() );
				String qs = message.requestPath().split( "\\?" )[1];
				String[] params = qs.split( "&" );
				for( String param : params ) {
					String[] kv = param.split( "=" );
					qsValue.getNewChild( kv[0] ).setValue( kv[1] );
				}
			} catch( ArrayIndexOutOfBoundsException e ) {}
		}
	}
	
	public CommMessage recv( InputStream istream )
		throws IOException
	{
		HttpParser parser = new HttpParser( istream );
		HttpMessage message = parser.parse();
		HttpMessage.Version version = message.version();
		if ( version == null || version.equals( HttpMessage.Version.HTTP_1_1 ) ) {
			// The default is to keep the connection open, unless Connection: close is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "close" ) )
				channel.setToBeClosed( true );
			else
				channel.setToBeClosed( false );
		} else if ( version.equals( HttpMessage.Version.HTTP_1_0 ) ) {
			// The default is to close the connection, unless Connection: Keep-Alive is specified
			if ( message.getPropertyOrEmptyString( "connection" ).equalsIgnoreCase( "keep-alive" ) )
				channel.setToBeClosed( false );
			else
				channel.setToBeClosed( true );
		}

		if ( getParameterVector( "debug" ).first().intValue() > 0 ) {
			StringBuilder debugSB = new StringBuilder();
			debugSB.append( "[HTTP debug] Receiving:\n" );
			debugSB.append( "HTTP Code: " + message.httpCode() + "\n" );
			debugSB.append( "--> Header properties\n" );
			for( Entry< String, String > entry : message.properties() ) {
				debugSB.append( '\t' + entry.getKey() + ": " + entry.getValue() + '\n' );
			}
			for( HttpMessage.Cookie cookie : message.setCookies() ) {
				debugSB.append( "\tset-cookie: " + cookie.toString() + '\n' );
			}
			for( Entry< String, String > entry : message.cookies().entrySet() ) {
				debugSB.append( "\tcookie: " + entry.getKey() + '=' + entry.getValue() + '\n' );
			}
			debugSB.append( "--> Message content\n" );
			if ( message.content() != null )
				debugSB.append( new String( message.content() ) );
			Interpreter.getInstance().logger().info( debugSB.toString() );
		}
		
		CommMessage retVal = null;
		Value messageValue = Value.create();
		String opId = null;
		
		if ( message.size() > 0 ) {
			String format = getParameterVector( "format" ).first().strValue();
			String type = message.getProperty( "content-type" ).split( ";" )[0];
			if ( "application/x-www-form-urlencoded".equals( type ) ) {
				parseForm( message, messageValue );
			} else if ( "text/xml".equals( type ) || "rest".equals( format ) ) {
				parseXML( message, messageValue );
			} else if ( "text/x-gwt-rpc".equals( type ) ) {
				opId = parseGWTRPC( message, messageValue );
				requestFormat = "text/x-gwt-rpc";
			} else if ( "multipart/form-data".equals( type ) ) {
				parseMultiPartFormData( message, messageValue );
			} else {
				messageValue.setValue( new String( message.content() ) );
			}
		}
		
		if ( message.type() == HttpMessage.Type.RESPONSE ) {
			checkForSetCookie( message, messageValue );
			retVal = new CommMessage( inputId, "/", messageValue );
		} else if (
				message.type() == HttpMessage.Type.POST ||
				message.type() == HttpMessage.Type.GET ) {
			if ( opId == null ) {
				opId = message.requestPath().split( "\\?" )[0];
			}

			parseQueryString( message, messageValue );

			InputOperation op = null;
			try {
				op = Interpreter.getInstance().getInputOperation( opId );
			} catch( InvalidIdException iie ) {}
			
			if ( op == null || !channel.parentListener().canHandleInputOperation( op ) ) {
				String defaultOpId = getParameterVector( "default" ).first().strValue();
				if ( defaultOpId.length() > 0 ) {
					Value body = messageValue;
					messageValue = Value.create();
					messageValue.getChildren( "data" ).add( body );
					messageValue.getChildren( "operation" ).first().setValue( opId );
					opId = defaultOpId;
				}
			}
			
			String userAgent;
			if ( (userAgent=message.getProperty( "user-agent" )) != null ) {
				messageValue.getNewChild( Constants.Predefined.USER_AGENT.token().content() ).setValue( userAgent );
			}
			
			checkForCookies( message, messageValue );
			
			//TODO support resourcePath
			retVal = new CommMessage( opId, "/", messageValue );
		}
		
		received = true;
		
		return retVal;
	}
}