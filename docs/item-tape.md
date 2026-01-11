# Measuring Tape Item - Disabled

The Measuring Tape item is currently **disabled** pending implementation of the keystone feature.

## How to Re-enable

To re-enable the Measuring Tape, uncomment the following sections:

### 1. Item Registration (`src/main/java/.../registry/ModItems.java`)

```java
// Uncomment this:
public static final Item MEASURING_TAPE = register("measuring_tape",
        MeasuringTapeItem::new,
        new Item.Properties().stacksTo(1));

// Remove this line:
// public static final Item MEASURING_TAPE = null;
```

### 2. Give Command (`src/main/java/.../command/StrutturaCommand.java`)

In `executeGive()` method, uncomment:
```java
ItemStack tapeStack = new ItemStack(ModItems.MEASURING_TAPE);
boolean tapeAdded = player.getInventory().add(tapeStack);
```

And restore the original success/partial logic.

### 3. Attack Handler (`src/main/java/.../Architect.java`)

Uncomment:
```java
TapeAttackHandler.getInstance().register();
```

## Current Implementation Status

The Tape has the following logic already implemented:

- **Right-click on block**: Checks if block is in construction (base only, not rooms)
- **Left-click on block**: Same validation as right-click
- **Right/Left-click on entity**: Shows "no action yet" message if entity is in construction
- **Error messages**:
  - Not in editing mode
  - Editing a room (must be in base construction)
  - Block/entity not part of construction

## TODO

- Implement keystone block marking/unmarking
- Implement keystone GUI
- Define what keystone blocks do (spawn anchor points?)
