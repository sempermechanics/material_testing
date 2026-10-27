package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.App
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Device
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Engine
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Frame
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Metrics
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Node
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Roi
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Skipped
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Sweep
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.User
import com.sempermechanics.semper.util.LenientObjectListSerializer
import com.sempermechanics.semper.util.OptObjectSerializer
import com.sempermechanics.semper.util.StrictObjectListSerializer
import kotlinx.serialization.builtins.nullable

// The field serializers [SessionMetadataDoc]'s `@Serializable(with = …)` names,
// moved out of it under ADR-015's split rule (TD-229). Each reads its object or
// list by the org.json `opt…` rules (com.sempermechanics.semper.util.OrgJson).

internal object FramesSerializer : StrictObjectListSerializer<Frame>(Frame.serializer())

internal object AppSerializer : OptObjectSerializer<App>(App.serializer())

internal object DeviceSerializer : OptObjectSerializer<Device>(Device.serializer())

internal object UserSerializer : OptObjectSerializer<User>(User.serializer())

internal object EngineSerializer : OptObjectSerializer<Engine>(Engine.serializer())

internal object RoiSerializer : OptObjectSerializer<Roi>(Roi.serializer())

internal object SweepSerializer : OptObjectSerializer<Sweep>(Sweep.serializer())

internal object SkippedSerializer : OptObjectSerializer<Skipped>(Skipped.serializer())

internal object MetricsSerializer : OptObjectSerializer<Metrics>(Metrics.serializer())

internal object NodesSerializer : LenientObjectListSerializer<Node>(Node.serializer().nullable)
