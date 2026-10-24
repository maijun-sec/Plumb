package com.acme.shop.framework;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * A MyBatis mapper.
 *
 * <p>The two placeholder forms have opposite safety properties:
 *
 * <ul>
 *   <li>{@code #{name}} becomes a PreparedStatement placeholder. The value is bound as data
 *       and cannot change the statement. SAFE.
 *   <li>{@code ${name}} is a raw string substitution. The value becomes part of the SQL text
 *       and CAN change the statement. UNSAFE.
 * </ul>
 *
 * <p>{@code ${}} has legitimate uses -- a dynamic table name, a dynamic column, an ORDER BY
 * direction -- because a prepared-statement placeholder cannot stand in for SQL structure. So a
 * {@code ${}} that names nothing the method receives is not a finding.
 */
public interface UserMapper {

  /** UNSAFE: the value is spliced into the SQL text. */
  @Select("SELECT * FROM users WHERE name = '${name}'")
  Object findByName(String name);

  /** SAFE: a bound parameter. */
  @Select("SELECT * FROM users WHERE name = #{name}")
  Object findByNameBound(String name);

  /** SAFE: the ${} names a table the method does not receive, so the caller cannot influence it. */
  @Select("SELECT * FROM ${tableName} WHERE id = #{id}")
  Object findById(Long id);

  /** UNSAFE: a write statement built by substitution. */
  @Update("UPDATE users SET name = '${name}' WHERE id = #{id}")
  int rename(String name, Long id);

  /** UNSAFE: a delete statement built by substitution. */
  @Delete("DELETE FROM users WHERE name = '${name}'")
  int delete(String name);

  /** UNSAFE: an insert statement built by substitution. */
  @Insert("INSERT INTO users (name) VALUES ('${name}')")
  int insert(String name);

  /** SAFE: a bound parameter. */
  @Insert("INSERT INTO users (name) VALUES (#{name})")
  int insertBound(String name);
}
