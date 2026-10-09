package com.androidtoolsuite.app.host;

import org.json.JSONArray;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class HostFoldersTest {
    @Test public void emptyFoldersCanBeCreatedRenamedAndRestored() throws Exception {
        var created=HostFolders.save(List.of(),"empty","空文件夹",HostFolders.HOME,List.of());
        assertTrue(HostFolders.find(created,"empty").members.isEmpty());
        var renamed=HostFolders.save(created,"empty","资料",HostFolders.HOME,List.of());
        assertEquals("资料",HostFolders.decode(HostFolders.encode(renamed)).get(0).name);
        assertEquals(1,HostFolders.merge(List.of(),renamed).size());
    }
    @Test public void movingMembersKeepsOneParentAndNeverDeletesTheirConfiguration() throws Exception {
        var folders = List.of(
                new HostFolders.Folder("one","系统",HostFolders.HOME,List.of("shizuku:widget","access:widget")),
                new HostFolders.Folder("two","游戏",HostFolders.HOME,List.of("phi:rks")));
        var moved = HostFolders.move(folders,HostFolders.HOME,"shizuku:widget","two");
        assertEquals(List.of("access:widget"),HostFolders.find(moved,"one").members);
        assertEquals(List.of("phi:rks","shizuku:widget"),HostFolders.find(moved,"two").members);
        var root = HostFolders.move(moved,HostFolders.HOME,"access:widget",null);
        assertNotNull(HostFolders.find(root,"one"));
        assertTrue(HostFolders.find(root,"one").members.isEmpty());
        assertEquals(2,HostFolders.find(root,"two").members.size());
        assertEquals(1,HostFolders.dissolve(root,"two").size());
        assertTrue(HostFolders.find(HostFolders.dissolve(root,"two"),"one").members.isEmpty());
        assertEquals(2,folders.size());
        assertEquals(HostFolders.encode(moved).toString(),HostFolders.encode(HostFolders.decode(HostFolders.encode(moved))).toString());
    }

    @Test public void backupMergeRetainsCurrentMembershipAndAddsNewWidgets() {
        var current = List.of(new HostFolders.Folder("current","常用",HostFolders.HOME,List.of("a","b")));
        var incoming = List.of(new HostFolders.Folder("import","系统",HostFolders.HOME,List.of("b","c")),
                new HostFolders.Folder("tools","工具",HostFolders.TOOLS,List.of("b")));
        var merged = HostFolders.merge(current,incoming);
        assertEquals(List.of("a","b"),HostFolders.find(merged,"current").members);
        assertEquals(List.of("c"),HostFolders.find(merged,"import").members);
        assertEquals("tools",HostFolders.containing(merged,HostFolders.TOOLS,"b").id);
    }

    @Test public void malformedLayoutsAreRejectedInsteadOfSilentlyDiscardingFolders() throws Exception {
        var source = new JSONArray("[{\"id\":\"a\",\"name\":\"组\",\"scope\":\"home\",\"members\":[\"w\"]},{\"id\":\"b\",\"name\":\"组\",\"scope\":\"home\",\"members\":[\"w\"]}]");
        assertThrows(org.json.JSONException.class,()->HostFolders.decode(source));
        assertThrows(IllegalArgumentException.class,()->new HostFolders.Folder("a","组","unknown",List.of("w")));
        assertThrows(IllegalArgumentException.class,()->new HostFolders.Folder("a","组",HostFolders.HOME,List.of("@folder:b")));
    }
}
