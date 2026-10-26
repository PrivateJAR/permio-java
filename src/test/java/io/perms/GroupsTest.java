package io.perms;

import static io.perms.TestServer.reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.perms.proto.pagination.v1.PaginationResponse;
import io.perms.proto.permissions.v1.AddGroupMembersRequest;
import io.perms.proto.permissions.v1.AddGroupMembersResponse;
import io.perms.proto.permissions.v1.CreateGroupRequest;
import io.perms.proto.permissions.v1.DeleteGroupRequest;
import io.perms.proto.permissions.v1.GetGroupRequest;
import io.perms.proto.permissions.v1.Group;
import io.perms.proto.permissions.v1.GroupMember;
import io.perms.proto.permissions.v1.ListGroupMembersRequest;
import io.perms.proto.permissions.v1.ListGroupMembersResponse;
import io.perms.proto.permissions.v1.ListGroupsForPrincipalRequest;
import io.perms.proto.permissions.v1.ListGroupsForPrincipalResponse;
import io.perms.proto.permissions.v1.ListGroupsRequest;
import io.perms.proto.permissions.v1.ListGroupsResponse;
import io.perms.proto.permissions.v1.PermissionsServiceGrpc;
import io.perms.proto.permissions.v1.RemoveGroupMembersRequest;
import io.perms.proto.permissions.v1.RemoveGroupMembersResponse;
import io.perms.proto.permissions.v1.UpdateGroupRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupsTest {
  private TestServer server;
  private Permio permio;
  private final Map<String, Object> last = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    server =
        new TestServer(
            new PermissionsServiceGrpc.PermissionsServiceImplBase() {
              private Group group(String project, String name, String description) {
                return Group.newBuilder()
                    .setProjectName(project)
                    .setName(name)
                    .setDescription(description)
                    .setPrincipalId("group:" + name)
                    .build();
              }

              @Override
              public void createGroup(CreateGroupRequest r, StreamObserver<Group> o) {
                last.put("create", r);
                reply(o, () -> group(r.getProjectName(), r.getName(), r.getDescription()));
              }

              @Override
              public void getGroup(GetGroupRequest r, StreamObserver<Group> o) {
                if (r.getName().equals("missing")) {
                  o.onError(
                      Status.NOT_FOUND.withDescription("group not found").asRuntimeException());
                  return;
                }
                reply(o, () -> group(r.getProjectName(), r.getName(), ""));
              }

              @Override
              public void updateGroup(UpdateGroupRequest r, StreamObserver<Group> o) {
                last.put("update", r);
                reply(o, () -> group(r.getProjectName(), r.getName(), r.getDescription()));
              }

              @Override
              public void deleteGroup(DeleteGroupRequest r, StreamObserver<Empty> o) {
                last.put("delete", r);
                reply(o, Empty::getDefaultInstance);
              }

              @Override
              public void listGroups(ListGroupsRequest r, StreamObserver<ListGroupsResponse> o) {
                last.put("list", r);
                boolean first = !r.getPagination().hasCursor();
                reply(
                    o,
                    () ->
                        ListGroupsResponse.newBuilder()
                            .addGroups(group(r.getProjectName(), first ? "a" : "b", ""))
                            .setPagination(
                                PaginationResponse.newBuilder().setNextCursor(first ? "next" : ""))
                            .build());
              }

              @Override
              public void addGroupMembers(
                  AddGroupMembersRequest r, StreamObserver<AddGroupMembersResponse> o) {
                last.put("add", r);
                reply(
                    o,
                    () ->
                        AddGroupMembersResponse.newBuilder()
                            .addAddedPrincipalIds(r.getPrincipalIds(0))
                            .addAllExistingPrincipalIds(
                                r.getPrincipalIdsList().subList(1, r.getPrincipalIdsCount()))
                            .build());
              }

              @Override
              public void removeGroupMembers(
                  RemoveGroupMembersRequest r, StreamObserver<RemoveGroupMembersResponse> o) {
                last.put("remove", r);
                reply(
                    o,
                    () ->
                        RemoveGroupMembersResponse.newBuilder()
                            .addRemovedPrincipalIds(r.getPrincipalIds(0))
                            .addAllNotMemberPrincipalIds(
                                r.getPrincipalIdsList().subList(1, r.getPrincipalIdsCount()))
                            .build());
              }

              @Override
              public void listGroupMembers(
                  ListGroupMembersRequest r, StreamObserver<ListGroupMembersResponse> o) {
                last.put("members", r);
                reply(
                    o,
                    () ->
                        ListGroupMembersResponse.newBuilder()
                            .addMembers(GroupMember.newBuilder().setPrincipalId("bob"))
                            .build());
              }

              @Override
              public void listGroupsForPrincipal(
                  ListGroupsForPrincipalRequest r,
                  StreamObserver<ListGroupsForPrincipalResponse> o) {
                last.put("forPrincipal", r);
                reply(
                    o,
                    () ->
                        ListGroupsForPrincipalResponse.newBuilder()
                            .addGroups(group(r.getProjectName(), "editors", ""))
                            .build());
              }
            });
    permio = server.client();
  }

  @AfterEach
  void tearDown() {
    permio.close();
    server.close();
  }

  @Test
  void principalHelper() {
    assertThat(Groups.principal("editors")).isEqualTo("group:editors");
    assertThat(Permio.group("a-1")).isEqualTo("group:a-1");
    for (String bad : new String[] {"", "Editors", "a b", "a_b", "x".repeat(65)}) {
      assertThatThrownBy(() -> Groups.principal(bad))
          .as(bad)
          .isInstanceOf(PermioValidationException.class);
    }
  }

  @Test
  void crud() {
    Group g = permio.groups().create("editors", "Can edit");
    assertThat(g.getPrincipalId()).isEqualTo("group:editors");
    CreateGroupRequest create = (CreateGroupRequest) last.get("create");
    assertThat(create.getProjectName()).isEqualTo("my-app");
    assertThat(create.getDescription()).isEqualTo("Can edit");

    assertThat(permio.groups().get("group:editors").getName()).isEqualTo("editors");
    assertThatThrownBy(() -> permio.groups().get("missing"))
        .isInstanceOf(PermioNotFoundException.class);

    assertThat(permio.groups().update("editors", "new").getDescription()).isEqualTo("new");
    permio.withProject("other").groups().delete("group:editors");
    DeleteGroupRequest delete = (DeleteGroupRequest) last.get("delete");
    assertThat(delete.getName()).isEqualTo("editors");
    assertThat(delete.getProjectName()).isEqualTo("other");
  }

  @Test
  void listsAndMembers() {
    List<String> names =
        permio.groups().list(ListOptions.defaults().withSearch("e")).stream()
            .map(Group::getName)
            .collect(Collectors.toList());
    assertThat(names).containsExactly("a", "b");
    assertThat(((ListGroupsRequest) last.get("list")).getSearch()).isEqualTo("e");

    AddMembersResult added = permio.groups().addMembers("editors", List.of("bob", "carol", "bob"));
    assertThat(added.added()).containsExactly("bob");
    assertThat(added.existing()).containsExactly("carol");
    assertThat(((AddGroupMembersRequest) last.get("add")).getPrincipalIdsList())
        .containsExactly("bob", "carol");

    RemoveMembersResult removed = permio.groups().removeMembers("editors", List.of("bob", "x"));
    assertThat(removed.removed()).containsExactly("bob");
    assertThat(removed.notMembers()).containsExactly("x");

    assertThat(permio.groups().listMembers("editors").toList())
        .extracting(GroupMember::getPrincipalId)
        .containsExactly("bob");
    assertThat(permio.groups().listForPrincipal("bob").toList())
        .extracting(Group::getName)
        .containsExactly("editors");
    assertThat(((ListGroupsForPrincipalRequest) last.get("forPrincipal")).getPrincipalId())
        .isEqualTo("bob");
  }

  @Test
  void memberValidation() {
    assertThatThrownBy(() -> permio.groups().addMembers("editors", List.of("group:other")))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("cannot contain groups");
    assertThatThrownBy(() -> permio.groups().addMembers("editors", List.of()))
        .isInstanceOf(PermioValidationException.class);
    List<String> many = IntStream.range(0, 101).mapToObj(i -> "p" + i).collect(Collectors.toList());
    assertThatThrownBy(() -> permio.groups().removeMembers("editors", many))
        .isInstanceOf(PermioValidationException.class)
        .hasMessageContaining("at most 100");
    assertThatThrownBy(() -> permio.groups().create("Bad Name"))
        .isInstanceOf(PermioValidationException.class);
    assertThat(server.calls).isEmpty();
  }
}
