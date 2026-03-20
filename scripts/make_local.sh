#!/usr/bin/env bash

set -e

MODEL_NAME=$1

if [ -z "$MODEL_NAME" ]; then
  echo "Usage: ./make_local.sh ExampleModel"
  exit 1
fi

BASE_PACKAGE="com.achulkov.evomolior"
BASE_PATH=$(echo "$BASE_PACKAGE" | tr '.' '/')

LOWER_NAME=$(echo "$MODEL_NAME" | sed 's/\(.[A-Z]\)/_\1/g' | tr '[:upper:]' '[:lower:]' | sed 's/^_//')
ENTITY_NAME="${MODEL_NAME}Entity"
DAO_NAME="${MODEL_NAME}Dao"
MAPPER_NAME="${MODEL_NAME}EntityMapper"
DATASOURCE_NAME="${MODEL_NAME}LocalDataSource"
IMPL_NAME="${MODEL_NAME}LocalDataSourceImpl"
INMEMORY_IMPL_NAME="InMemory${MODEL_NAME}LocalDataSource"

DOMAIN_PACKAGE="composeApp/src/commonMain/kotlin/$BASE_PATH/domain/model"
DOMAIN_FILE="$DOMAIN_PACKAGE/$MODEL_NAME.kt"

COMMON_MAIN="composeApp/src/commonMain/kotlin/$BASE_PATH/data/source/local"
NONWEB_MAIN="composeApp/src/nonWebMain/kotlin/$BASE_PATH/data/source/local"
WEB_MAIN="composeApp/src/webMain/kotlin/$BASE_PATH/data/source/local"

DAO_DIR="$NONWEB_MAIN/dao"
ENTITY_DIR="$NONWEB_MAIN/entity"
IMPL_DIR="$NONWEB_MAIN/impl"
WEB_IMPL_DIR="$WEB_MAIN/impl"

mkdir -p "$DOMAIN_PACKAGE"
mkdir -p "$COMMON_MAIN"
mkdir -p "$DAO_DIR"
mkdir -p "$ENTITY_DIR"
mkdir -p "$IMPL_DIR"
mkdir -p "$WEB_IMPL_DIR"


if [ -f "$DOMAIN_FILE" ]; then
    echo "Domain model '$MODEL_NAME' already exists — skipping."
else
    cat <<EOF > "$DOMAIN_FILE"
package $BASE_PACKAGE.domain.model

data class $MODEL_NAME(
    val id: String
)
EOF

    echo "Created: $DOMAIN_FILE"
fi


################################
# LocalDataSource interface
################################
FILE1="$COMMON_MAIN/${DATASOURCE_NAME}.kt"
cat > "$FILE1" <<EOF
package $BASE_PACKAGE.data.source.local

import $BASE_PACKAGE.domain.model.$MODEL_NAME

interface ${DATASOURCE_NAME} : LocalDataSource<String, $MODEL_NAME> {
  //Add more functions if needed
}
EOF
echo "Created: $FILE1"

################################
# Entity + Mapper
################################
FILE2="$ENTITY_DIR/${ENTITY_NAME}.kt"
cat > "$FILE2" <<EOF
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package $BASE_PACKAGE.data.source.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import $BASE_PACKAGE.domain.model.$MODEL_NAME
import kotlin.uuid.Uuid

@Entity(tableName = "$LOWER_NAME")
data class $ENTITY_NAME(
    @PrimaryKey @ColumnInfo("id") val id: String = Uuid.random().toString(),
)

class $MAPPER_NAME : EntityMapper<$ENTITY_NAME, $MODEL_NAME> {
    override fun toEntity(model: $MODEL_NAME): $ENTITY_NAME {
        return $ENTITY_NAME(
            id = model.id
        )
    }

    override fun toModel(entity: $ENTITY_NAME): $MODEL_NAME {
        return $MODEL_NAME(
            id = entity.id
        )
    }
}
EOF
echo "Created: $FILE2"

################################
# DAO
################################
FILE3="$DAO_DIR/${DAO_NAME}.kt"
cat > "$FILE3" <<EOF
package $BASE_PACKAGE.data.source.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import $BASE_PACKAGE.data.source.local.entity.$ENTITY_NAME
import kotlinx.coroutines.flow.Flow

@Dao
interface $DAO_NAME : BaseRoomDao<String, $ENTITY_NAME> {

    @Query("SELECT * FROM $LOWER_NAME WHERE id = :id")
    override suspend fun getById(id: String): $ENTITY_NAME?

    @Query("SELECT * FROM $LOWER_NAME WHERE id = :id")
    override fun getByIdFlow(id: String): Flow<$ENTITY_NAME?>

    @Query("SELECT * FROM $LOWER_NAME")
    override fun getAllFlow(): Flow<List<$ENTITY_NAME>>

    @Query("SELECT * FROM $LOWER_NAME")
    override suspend fun getAll(): List<$ENTITY_NAME>

    @Upsert
    override suspend fun upsert(entity: $ENTITY_NAME)

    @Query("DELETE FROM $LOWER_NAME WHERE id = :id")
    override suspend fun deleteById(id: String)

    @Delete
    override suspend fun delete(entity: $ENTITY_NAME)

    @Query("DELETE FROM $LOWER_NAME")
    override suspend fun deleteAll()
}
EOF
echo "Created: $FILE3"

################################
# Room LocalDataSource Impl
################################
FILE4="$IMPL_DIR/${IMPL_NAME}.kt"
cat > "$FILE4" <<EOF
package $BASE_PACKAGE.data.source.local.impl

import $BASE_PACKAGE.data.source.local.$DATASOURCE_NAME
import $BASE_PACKAGE.data.source.local.dao.$DAO_NAME
import $BASE_PACKAGE.data.source.local.entity.$ENTITY_NAME
import $BASE_PACKAGE.data.source.local.entity.$MAPPER_NAME
import $BASE_PACKAGE.domain.model.$MODEL_NAME

class $IMPL_NAME(
    dao: $DAO_NAME,
    mapper: $MAPPER_NAME = $MAPPER_NAME()
) : BaseRoomLocalDataSource<String, $ENTITY_NAME, $MODEL_NAME>(
    dao = dao,
    mapper = mapper
), $DATASOURCE_NAME
EOF
echo "Created: $FILE4"

################################
# In-Memory Impl (webMain)
################################
FILE5="$WEB_IMPL_DIR/${INMEMORY_IMPL_NAME}.kt"
cat > "$FILE5" <<EOF
package $BASE_PACKAGE.data.source.local.impl

import $BASE_PACKAGE.data.source.local.$DATASOURCE_NAME
import $BASE_PACKAGE.domain.model.$MODEL_NAME

class $INMEMORY_IMPL_NAME :
    BaseInMemoryLocalDataSource<String, $MODEL_NAME>({ it.id }),
    $DATASOURCE_NAME
EOF
echo "Created: $FILE5"



################################
# Update AppDatabase.kt
################################
APP_DB_FILE="$NONWEB_MAIN/AppDatabase.kt"

if [ -f "$APP_DB_FILE" ]; then
  # Insert Entity into @Database entities list
  sed -i '' "s/entities = \[/entities = \[$ENTITY_NAME::class, /" "$APP_DB_FILE"

  # Insert imports after package line
  sed -i '' "/^package /a\\
import $BASE_PACKAGE.data.source.local.entity.$ENTITY_NAME
  " "$APP_DB_FILE"

  sed -i '' "/^package /a\\
import $BASE_PACKAGE.data.source.local.dao.$DAO_NAME
  " "$APP_DB_FILE"


  # Add abstract DAO function
  sed -i '' "/\/\/Add other DAOs here/i\\
    abstract fun ${LOWER_NAME}Dao(): $DAO_NAME
" "$APP_DB_FILE"


  echo "Updated: $APP_DB_FILE"
else
  echo "⚠️ Warning: AppDatabase.kt not found — skipping."
fi

################################
# Update Di.kt (nonWebMain)
################################
DI_FILE="$NONWEB_MAIN/DI.kt"

if [ -f "$DI_FILE" ]; then
  # Add DAO registration
  sed -i '' "/\/\/Local DAOs/a\\
    single { get<AppDatabase>().${LOWER_NAME}Dao() }
" "$DI_FILE"

  # Add Impl registration
  sed -i '' "/\/\/ Impl/a\\
    single { ${IMPL_NAME}(get()) } bind ${DATASOURCE_NAME}::class
" "$DI_FILE"

  # Add imports
  sed -i '' "/^package /a\\
import $BASE_PACKAGE.data.source.local.impl.$IMPL_NAME
  " "$DI_FILE"


  echo "Updated: $DI_FILE"
else
  echo "⚠️ Warning: Di.kt not found — skipping."
fi

################################
# Update Platform.web.kt
################################
WEB_PLATFORM_FILE="composeApp/src/webMain/kotlin/$BASE_PATH/util/Platform.web.kt"

if [ -f "$WEB_PLATFORM_FILE" ]; then
  # Add in-memory binding
  sed -i '' "/\/\/ Impl/a\\
    singleOf(::${INMEMORY_IMPL_NAME}) bind ${DATASOURCE_NAME}::class
" "$WEB_PLATFORM_FILE"

  # Add imports
  sed -i '' "/^package /a\\
import $BASE_PACKAGE.data.source.local.$DATASOURCE_NAME
  " "$WEB_PLATFORM_FILE"

  sed -i '' "/^package /a\\
import $BASE_PACKAGE.data.source.local.impl.$INMEMORY_IMPL_NAME
  " "$WEB_PLATFORM_FILE"


  echo "Updated: $WEB_PLATFORM_FILE"
else
  echo "⚠️ Warning: Platform.web.kt not found — skipping."
fi





echo "✅ Created Local Data Layer for: $MODEL_NAME"
