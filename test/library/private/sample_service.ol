type T1: void

type T2: void {
  .field:string
}

type T3: void {
  .fieldChoice: double
}
|
string {
  .fieldChoice2: string
}

type T4: T1 | T2

type T5: void {
  .fieldChoice: double
}
|
string {
  .fieldChoice2: string
}
|
int {
  .fieldChoice3: raw
}

type T6: void | string | int 

type T7: undefined

interface TmpInterface {
  RequestResponse:
    tmp( T1 )( T2 ) throws Fault1( T3 ),
    tmp2( T3 )( T4 ) throws Fault2,
    tmp3( T5 )( T6 ) throws Fault3( string ) Fault4( T7 )
}

inputPort TPort {
  Location: "socket://localhost:9000"
  Protocol: sodep
  Interfaces: TmpInterface
}

main {
  tmp()( response ) {
    response.field = "test"
  }
}
