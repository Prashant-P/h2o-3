package water.rapids;

import water.*;
import water.fvec.*;
import water.util.*;

import java.util.Arrays;

/** Ddply
 *  Group the rows of 'data' by unique combinations of '[group-by-cols]'.
 *  Apply any function 'fcn' to a group Frame, which must accept a Frame (and
 *  any "extra" arguments) and return a single scalar value.
 *
 *  Returns a set of grouping columns, with the single answer column, with one
 *  row per unique group.
 *
 */
class ASTDdply extends ASTPrim {
  @Override public String[] args() { return new String[]{"ary", "groupByCols", "fun"}; }
  @Override int nargs() { return 1+3; } // (ddply data [group-by-cols] fcn )
  @Override public String str() { return "ddply"; }
  @Override
  public Val apply(Env env, Env.StackHelp stk, AST asts[]) {
    Frame fr = stk.track(asts[1].exec(env)).getFrame();
    int ncols = fr.numCols();

    ASTNumList groupby = ASTGroup.check(ncols, asts[2]);
    int[] gbCols = groupby.expand4();

    AST fun = asts[3].exec(env).getFun();
    ASTFun scope = env._scope;  // Current execution scope; needed to lookup variables

    // Pass 1: Find all the groups (and count rows-per-group)
    IcedHashMap<ASTGroup.G,String> gss = ASTGroup.doGroups(fr,gbCols,ASTGroup.aggNRows());
    final ASTGroup.G[] grps = gss.keySet().toArray(new ASTGroup.G[gss.size()]);

    // apply an ORDER by here...
    final int[] ordCols = new ASTNumList(0,gbCols.length).expand4();
    Arrays.sort(grps,new java.util.Comparator<ASTGroup.G>() {
        // Compare 2 groups.  Iterate down _gs, stop when _gs[i] > that._gs[i],
        // or _gs[i] < that._gs[i].  Order by various columns specified by
        // _orderByCols.  NaN is treated as least
        @Override public int compare( ASTGroup.G g1, ASTGroup.G g2 ) {
          for( int i : ordCols ) {
            if(  Double.isNaN(g1._gs[i]) && !Double.isNaN(g2._gs[i]) ) return -1;
            if( !Double.isNaN(g1._gs[i]) &&  Double.isNaN(g2._gs[i]) ) return  1;
            if( g1._gs[i] != g2._gs[i] ) return g1._gs[i] < g2._gs[i] ? -1 : 1;
          }
          return 0;
        }
        // I do not believe sort() calls equals() at this time, so no need to implement
        @Override public boolean equals( Object o ) { throw H2O.unimpl(); }
      });

    // Uniquely number the groups
    for( int gnum=0; gnum<grps.length; gnum++ ) grps[gnum]._dss[0][0] = gnum;

    // Pass 2: Build all the groups, building 1 Vec per-group, with exactly the
    // same Chunk layout, except each Chunk will be the filter rows numbers; a
    // list of the Chunk-relative row-numbers for that group in an original
    // data Chunk.  Each Vec will have a *different* number of rows.
    byte [][] types = new byte[gss.size()][1];
    for(int i = 0; i < types.length; ++i)
      types[i][0] = Vec.T_NUM;
    Vec [] vgrps = new BuildGroup(gbCols,gss).doAll(types, fr.vecs()).close();

    // Pass 3: For each group, build a full frame for the group, run the
    // function on it and tear the frame down.
    final RemoteRapids[] remoteTasks = new RemoteRapids[gss.size()]; // gather up the remote tasks...
    Futures fs = new Futures();
    for( int i=0; i<remoteTasks.length; i++ )
      fs.add(RPC.call(vgrps[i]._key.home_node(), remoteTasks[i] = new RemoteRapids(fr, new VecAry(vgrps[i]), fun, scope)));
    fs.blockForPending();

    // Build the output!
    final double[] res0 = remoteTasks[0]._result;
    String[] fcnames = new String[res0.length];
    for( int i=0; i<res0.length; i++ )
      fcnames[i] = "ddply_C"+(i+1);

    MRTask mrfill = new MRTask() {
      @Override public void map(Chunk[] c, NewChunk[] ncs) {
        int start=(int)c[0].start();
        for( int i=0;i<c[0]._len;++i) {
          ASTGroup.G g = grps[i+start];  // One Group per row
          int j;
          for( j=0; j<g._gs.length; j++ ) // The Group Key, as a row
            ncs[j].addNum(g._gs[j]);
          double[] res = remoteTasks[i+start]._result;
          for( int a=0; a<res0.length; a++ )
            ncs[j++].addNum(res[a]);
        }
      }
      };

    Frame f = ASTGroup.buildOutput(gbCols, res0.length, fr, fcnames, gss.size(), mrfill);
    return new ValFrame(f);
  }

  // --------------------------------------------------------------------------
  // Build all the groups, building 1 Vec per-group, with exactly the same
  // Chunk layout, except each Chunk will be the filter rows numbers; a list
  // of the Chunk-relative row-numbers for that group in an original data Chunk.
  private static class BuildGroup extends MRTask<BuildGroup> {
    final IcedHashMap<ASTGroup.G,String> _gss;
    final int[] _gbCols;
    BuildGroup( int[] gbCols, IcedHashMap<ASTGroup.G,String> gss ) { _gbCols = gbCols; _gss = gss; }
    @Override public void map( Chunk[] cs, NewChunk[] ncs ) {
      ASTGroup.G gWork = new ASTGroup.G(_gbCols.length,null); // Working Group
      for( int row=0; row<cs[0]._len; row++ ) {
        gWork.fill(row,cs,_gbCols); // Fill the worker Group for the hashtable lookup
        int gnum = (int)_gss.getk(gWork)._dss[0][0]; // Existing group number
        ncs[gnum].addNum(row);  // gather row-numbers per-chunk per-group
      }
    }
    // Gather all the output Vecs.  Note that each Vec has a *different* number
    // of rows, and taken together they do NOT make a valid Frame.
    Vec[] close() {
      Futures fs = new Futures();
      Vec [] grps = new Vec[_appendables.length];
      for(int i = 0; i < grps.length; ++i)
        grps[i] = (Vec)_appendables[i].layout_and_close(fs).getAVecRaw(0);
      fs.blockForPending();
      return grps;
    }
  }

  // --------------------------------------------------------------------------
  private static class RemoteRapids extends DTask<RemoteRapids> {
    private Frame _data;        // Data frame
    private VecAry _gVec;     // the group to process...
    private AST _fun;           // the ast to execute on the group
    private ASTFun _scope;      // Execution environment
    private double[] _result;   // result is 1 row per group!

    RemoteRapids( Frame data, VecAry gVec, AST fun, ASTFun scope) {
      _data = data; _gVec=gVec; _fun=fun; _scope = scope;
    }

    @Override public void compute2() {
      // Make a group Frame, using wrapped Vecs wrapping the original data
      // frame with the filtered Vec passed in.  Run the function, getting a
      // scalar or a 1-row Frame back out.  Delete the group Frame.  Return the
      // 1-row Frame as a double[] of results for this group.

      // Make the subset Frame Vecs, no chunks yet
      Key<AVec>[] groupKeys = _gVec.group().addVecs(_data.numCols());
      final Vec[] groupVecs = new Vec[_data.numCols()];
      Futures fs = new Futures();
      for( int i=0; i<_data.numCols(); i++ )
        DKV.put(groupVecs[i] = new Vec(groupKeys[i], _gVec.rowLayout(), _gVec.domain(0), _gVec.type(0)), fs);
      fs.blockForPending();
      // Fill in the chunks
      new MRTask() {
        @Override public void setupLocal() {
          VecAry data_vecs = _data.vecs();
          for( int i=0; i<_gVec.nChunks(); i++ )
            if( data_vecs.isHomedLocally(i) ) {
              Chunk rowchk = _gVec.getChunk(i,0);
              for( int col=0; col<data_vecs.len(); col++ )
                DKV.put( Vec.chunkKey(groupVecs[col]._key,i), new SubsetChunk(data_vecs.getChunk(i,col),rowchk,new SingleChunk(groupVecs[col],i)), _fs);
            }
        }
      }.doAllNodes();
      Frame groupFrame = new Frame(null,_data._names, new VecAry(groupVecs));

      // Now run the function on the group frame
      Session ses = new Session();
      // Build an environment with proper lookup scope, and execute in a temp session
      Val val = ses.exec(new ASTExec( new AST[]{_fun,new ASTFrame(groupFrame)}), _scope);
      val = ses.end(val);
      
      // Result into a double[]
      if( val.isFrame() ) {
        Frame res = val.getFrame();
        if( res.numRows() != 1 )
          throw new IllegalArgumentException("ddply must return a 1-row (many column) frame, found "+res.numRows());
        _result = new double[res.numCols()];
        VecAry.Reader r = res.vecs().reader(false);
        for( int i=0; i<res.numCols(); i++ )
          _result[i] = r.at(0,i);
      } else if( val.isNum() ) {
        _result = new double[]{val.getNum()};
      } else if( val.isNums() ) {
        _result = val.getNums();
      } else throw new IllegalArgumentException("ddply must return either a number or a frame, not a "+val);
      
      
      // Cleanup
      groupFrame.delete();      // Delete the Frame holding WrappedVecs over SubsetChunks
      _gVec.remove();            // Delete the group-defining Vec
      _gVec = null;
      _data = null;             // Nuke to avoid returning (not for GC)
      _fun = null;              // Nuke to avoid returning (not for GC)
      _scope = null;            // Nuke to avoid returning (not for GC)
      // And done!
      tryComplete();
    }
  }

}
