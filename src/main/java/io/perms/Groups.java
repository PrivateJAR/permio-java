package io.perms;

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
import io.perms.proto.permissions.v1.RemoveGroupMembersRequest;
import io.perms.proto.permissions.v1.RemoveGroupMembersResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Groups of principals, available as {@link Permio#groups()}. A group is referenced as the
 * principal {@code group:<slug>} (see {@link #principal(String)}): grant to it and every member
 * holds the grant. Groups cannot contain groups.
 *
 * <p>Every method that takes a group accepts its slug ({@code "editors"}) or its principal ID
 * ({@code "group:editors"}). The project is the client's (see {@link Permio#withProject}).
 */
public final class Groups {
  /** Prefix of the principal ID that grants to a group are made to. */
  public static final String GROUP_PRINCIPAL_PREFIX = "group:";

  private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,64}$");

  private final Permio permio;

  Groups(Permio permio) {
    this.permio = permio;
  }

  /**
   * The principal ID of a group, {@code "group:<slug>"}. Grant permissions and roles to it, or
   * check it, like any other principal; every member of the group holds what the group is granted.
   *
   * @throws PermioValidationException if the slug is not 1-64 lower-case letters, digits and
   *     hyphens
   */
  public static String principal(String slug) {
    return GROUP_PRINCIPAL_PREFIX + assertSlug(slug);
  }

  /**
   * Creates a group.
   *
   * @throws PermioException with code {@code ALREADY_EXISTS} if the project already has a group
   *     with this name
   */
  public Group create(String name) {
    return create(name, "");
  }

  /** Creates a group with a description. */
  public Group create(String name, String description) {
    String slug = slugOf(name);
    String project = permio.resolveProject();
    return permio.call(
        () ->
            permio
                .stub()
                .createGroup(
                    CreateGroupRequest.newBuilder()
                        .setProjectName(project)
                        .setName(slug)
                        .setDescription(description == null ? "" : description)
                        .build()));
  }

  /**
   * Returns a group.
   *
   * @throws PermioNotFoundException if it does not exist
   */
  public Group get(String name) {
    String slug = slugOf(name);
    String project = permio.resolveProject();
    return permio.call(
        () ->
            permio
                .stub()
                .getGroup(
                    GetGroupRequest.newBuilder().setProjectName(project).setName(slug).build()));
  }

  /**
   * Replaces a group's description. A group's name cannot be changed.
   *
   * @throws PermioNotFoundException if it does not exist
   */
  public Group update(String name, String description) {
    String slug = slugOf(name);
    if (description == null) {
      throw new PermioValidationException("permio: description is required");
    }
    String project = permio.resolveProject();
    return permio.call(
        () ->
            permio
                .stub()
                .updateGroup(
                    io.perms.proto.permissions.v1.UpdateGroupRequest.newBuilder()
                        .setProjectName(project)
                        .setName(slug)
                        .setDescription(description)
                        .build()));
  }

  /**
   * Deletes a group, its memberships and every grant made to it. Succeeds whether or not the group
   * existed.
   */
  public void delete(String name) {
    String slug = slugOf(name);
    String project = permio.resolveProject();
    permio.call(
        () ->
            permio
                .stub()
                .deleteGroup(
                    DeleteGroupRequest.newBuilder().setProjectName(project).setName(slug).build()));
  }

  /** Iterates every group in the project, ordered by name. */
  public PagedIterable<Group> list() {
    return list(ListOptions.defaults());
  }

  /** Iterates the groups in the project, filtered by {@link ListOptions#withSearch}. */
  public PagedIterable<Group> list(ListOptions options) {
    String project = permio.resolveProject();
    return new PagedIterable<>(
        p -> {
          ListGroupsRequest.Builder req =
              ListGroupsRequest.newBuilder().setProjectName(project).setPagination(p);
          if (options.search() != null) {
            req.setSearch(options.search());
          }
          ListGroupsResponse res = permio.stub().listGroups(req.build());
          return new PagedIterable.Page<>(res.getGroupsList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /**
   * Adds up to 100 principals to a group. Principals that are already members are reported in
   * {@link AddMembersResult#existing()}. Group principals ({@code group:...}) are rejected, because
   * groups cannot be nested.
   */
  public AddMembersResult addMembers(String name, Collection<String> principals) {
    String slug = slugOf(name);
    List<String> ids = principalList(principals);
    for (String p : ids) {
      if (p.startsWith(GROUP_PRINCIPAL_PREFIX)) {
        throw new PermioValidationException("permio: groups cannot contain groups");
      }
    }
    String project = permio.resolveProject();
    AddGroupMembersResponse res =
        permio.call(
            () ->
                permio
                    .stub()
                    .addGroupMembers(
                        AddGroupMembersRequest.newBuilder()
                            .setProjectName(project)
                            .setName(slug)
                            .addAllPrincipalIds(ids)
                            .build()));
    return new AddMembersResult(res.getAddedPrincipalIdsList(), res.getExistingPrincipalIdsList());
  }

  /**
   * Removes up to 100 principals from a group. Principals that were not members are reported in
   * {@link RemoveMembersResult#notMembers()}.
   */
  public RemoveMembersResult removeMembers(String name, Collection<String> principals) {
    String slug = slugOf(name);
    List<String> ids = principalList(principals);
    String project = permio.resolveProject();
    RemoveGroupMembersResponse res =
        permio.call(
            () ->
                permio
                    .stub()
                    .removeGroupMembers(
                        RemoveGroupMembersRequest.newBuilder()
                            .setProjectName(project)
                            .setName(slug)
                            .addAllPrincipalIds(ids)
                            .build()));
    return new RemoveMembersResult(
        res.getRemovedPrincipalIdsList(), res.getNotMemberPrincipalIdsList());
  }

  /** Iterates a group's members, ordered by principal ID. */
  public PagedIterable<GroupMember> listMembers(String name) {
    return listMembers(name, ListOptions.defaults());
  }

  /** Iterates a group's members with the given page size. */
  public PagedIterable<GroupMember> listMembers(String name, ListOptions options) {
    String slug = slugOf(name);
    String project = permio.resolveProject();
    return new PagedIterable<>(
        p -> {
          ListGroupMembersResponse res =
              permio
                  .stub()
                  .listGroupMembers(
                      ListGroupMembersRequest.newBuilder()
                          .setProjectName(project)
                          .setName(slug)
                          .setPagination(p)
                          .build());
          return new PagedIterable.Page<>(
              res.getMembersList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  /** Iterates the groups {@code principal} is a member of, ordered by name. */
  public PagedIterable<Group> listForPrincipal(String principal) {
    return listForPrincipal(principal, ListOptions.defaults());
  }

  /** Iterates the groups {@code principal} is a member of, with the given page size. */
  public PagedIterable<Group> listForPrincipal(String principal, ListOptions options) {
    if (principal == null || principal.isEmpty()) {
      throw new PermioValidationException("permio: principal is required");
    }
    String project = permio.resolveProject();
    return new PagedIterable<>(
        p -> {
          ListGroupsForPrincipalResponse res =
              permio
                  .stub()
                  .listGroupsForPrincipal(
                      ListGroupsForPrincipalRequest.newBuilder()
                          .setProjectName(project)
                          .setPrincipalId(principal)
                          .setPagination(p)
                          .build());
          return new PagedIterable.Page<>(res.getGroupsList(), res.getPagination().getNextCursor());
        },
        options.pageSize());
  }

  private static String assertSlug(String slug) {
    if (slug == null || !SLUG.matcher(slug).matches()) {
      throw new PermioValidationException(
          "permio: invalid group name "
              + (slug == null ? "null" : "\"" + slug + "\"")
              + ": use 1-64 lower-case letters, digits and hyphens");
    }
    return slug;
  }

  // Group methods accept a slug or a "group:<slug>" principal ID.
  private static String slugOf(String nameOrPrincipal) {
    String name =
        nameOrPrincipal != null && nameOrPrincipal.startsWith(GROUP_PRINCIPAL_PREFIX)
            ? nameOrPrincipal.substring(GROUP_PRINCIPAL_PREFIX.length())
            : nameOrPrincipal;
    return assertSlug(name);
  }

  private static List<String> principalList(Collection<String> principals) {
    if (principals == null) {
      throw new PermioValidationException("permio: principals are required");
    }
    List<String> values = new ArrayList<>(new LinkedHashSet<>(principals));
    if (values.isEmpty()) {
      throw new PermioValidationException("permio: at least one principal is required");
    }
    if (values.size() > 100) {
      throw new PermioValidationException("permio: at most 100 principals per call");
    }
    for (String p : values) {
      if (p == null || p.isEmpty()) {
        throw new PermioValidationException("permio: principal IDs must be non-empty strings");
      }
    }
    return values;
  }
}
