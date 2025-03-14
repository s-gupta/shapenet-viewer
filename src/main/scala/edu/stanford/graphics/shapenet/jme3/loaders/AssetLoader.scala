package edu.stanford.graphics.shapenet.jme3.loaders

import java.io.File

import com.jme3.math.Vector3f
import edu.stanford.graphics.shapenet.{jme3, Constants, UserDataConstants}
import edu.stanford.graphics.shapenet.common._
import edu.stanford.graphics.shapenet.data.DataManager
import edu.stanford.graphics.shapenet.jme3.JmeUtils
import edu.stanford.graphics.shapenet.jme3.loaders.AssetLoader._
import edu.stanford.graphics.shapenet.util._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Asset Loader for loading resources
 * (avoid calling it AssetManager as there are so many of those)
 * @author Angel Chang
 */
class AssetLoader(val assetCreator: AssetCreator,
                  val dataManager: DataManager,
                  val defaultUseSupportHierarchy: Boolean = false,
                  val modelCacheSize: Option[Int] = None,
                  val defaultLoadFormat: Option[LoadFormat.Value]) extends Loggable {
  val utf8Loader = new UTF8Loader(this)
  val modelCache = new SoftLRUCache[String,assetCreator.MODEL](modelCacheSize.getOrElse(16))
  private implicit val dm: DataManager = dataManager

  /** Use different loading options for different types of models */

  // Load options for loading models via utf8
  private val utf8LoadOptions = Map(
    "wss" -> new AssetLoader.LoadOptions(
      geometryPath = Option(Seq(Constants.WSS_DATA_DIR, "geometry").mkString("/")),
      materialsPath = Option(Seq(Constants.WSS_DATA_DIR, "texture").mkString("/")),
      normalizeRGB = false,
      ignoreZeroRGBs = true,
      invertTransparency = true,
      defaultColor = Array(0.0, 0.0, 0.0),
      loader = "utf8",
      modelIdToPath = (id: String) => Seq(Constants.WSS_DATA_DIR, "model",id + ".json").mkString("/")
    ),
    "archive3d" -> new AssetLoader.LoadOptions(
      doubleSided = true,
      ignoreZeroRGBs = true,
      loader = "utf8",
      defaultColor = Array(0.0, 0.0, 0.0),
      modelIdToPath = (id: String) => Seq(Constants.ARCHIVE3D_DATA_DIR, "models",id, id + ".json").mkString("/")
    ),
    "vf" -> new AssetLoader.LoadOptions(
      doubleSided = true,
      loader = "utf8",
      defaultColor = Array(0.0, 0.0, 0.0),
      modelIdToPath = (id: String) => Seq(Constants.VF_DATA_DIR, id + ".json").mkString("/")
    )
  )

  def clearCache(): Unit = {
    modelCache.clear()
  }

  // Load options for loading models via obj
  private val _useObjGz: Boolean = false
  private val _wssObjDir: String = if (_useObjGz) Constants.WSS_OBJGZ_DIR else Constants.WSS_OBJ_DIR
  private val objLoadOptions = Map(
    "wss" -> new AssetLoader.LoadOptions(
      normalizeRGB = false,
      ignoreZeroRGBs = true,
      invertTransparency = true,
      defaultColor = Array(0.0, 0.0, 0.0),
      // Because the mtl and obj are not stored in the same directory,
      // we need to have the asset locator look hard for where the mtl/obj files are
      // (this incurs overhead of 404 errors with default jme asset loader)
      // work around by hacking around and specifying the materialsPath...
      geometryPath = Option(_wssObjDir),
      materialsPath = Option(Constants.WSS_TEXTURE_DIR),
      compressionExt = if (_useObjGz) "gz" else null,
      loader = "obj",
      modelIdToPath = (id: String) => _wssObjDir + "/" + id + ".obj"
    ),
    "archive3d" -> new AssetLoader.LoadOptions(
      doubleSided = true,
      loader = "obj",
      modelIdToPath = (id: String) => Seq(Constants.ARCHIVE3D_DATA_DIR, "models",id, id + ".obj").mkString("/")
    ),
    "3dw" -> new AssetLoader.LoadOptions(
      //  /9/4/e/2/8/9c89059106bd8f74b0004a598cd/94e289c89059106bd8f74b0004a598cd/OBJ/94e289c89059106bd8f74b0004a598cd.obj
      //doubleSided = true,
      ignoreZeroRGBs = true,
      defaultColor = Array(0.0, 0.0, 0.0),
      loader = "kmz",
      modelIdToPath = (id: String) => {
        val (id1,id2) = id.splitAt(6)
        val prefix = id1.mkString("/") + id2
        Seq(Constants.SHAPENET_DATA_DIR, prefix, id, "Collada", id + ".kmz").mkString("/")
//        Seq(Constants.SHAPENET_DATA_DIR, prefix, id, "OBJ", id + ".obj").mkString("/")
      }
    ),
    "vf" -> new AssetLoader.LoadOptions(
      doubleSided = true,
      loader = "ply",
      modelIdToPath = (id: String) => Seq(Constants.VF_DATA_DIR, id + "d.ply").mkString("/")
    ),
    "yobi3d" -> new AssetLoader.LoadOptions(
      doubleSided = true,
      loader = "obj",
      modelIdToPath = (id: String) => Seq("data", "models", "bikes", "02834778", id, "model.obj").mkString("/")
    ),
    "raw" -> new AssetLoader.LoadOptions(
      modelIdToPath = (id: String) => id
    )
  )
  private val kmzLoadOptions = Map(
    "3dw" -> new AssetLoader.LoadOptions(
      //  /9/4/e/2/8/9c89059106bd8f74b0004a598cd/94e289c89059106bd8f74b0004a598cd/OBJ/94e289c89059106bd8f74b0004a598cd.obj
      //doubleSided = true,
      ignoreZeroRGBs = true,
      defaultColor = Array(0.0, 0.0, 0.0),
      loader = "kmz",
      modelIdToPath = (id: String) => {
        val (id1,id2) = id.splitAt(6)
        val prefix = id1.mkString("/") + id2
        Seq(Constants.SHAPENET_DATA_DIR, prefix, id, "Collada", id + ".kmz").mkString("/")
      }
    )
  )

  private val loadOptions = defaultLoadFormat.getOrElse(LoadFormat.OBJ_FORMAT) match {
    case LoadFormat.OBJ_FORMAT => objLoadOptions
    case LoadFormat.UTF8_FORMAT => utf8LoadOptions
  }

  def getModelLoadOptions(source: String): AssetLoader.LoadOptions = {
    loadOptions(source)
  }

  /** Load a material */
  def loadMaterial(materialInfo: MaterialInfo): assetCreator.MATERIAL = {
    assetCreator.createMaterial(materialInfo)
  }

  /** Load a model */
  def loadModel(modelId: String): assetCreator.MODEL = {
    val fullId = FullId(modelId)
    val options = loadOptions(fullId.source)
    val loadOpts = dataManager.getModelLoadOptions(fullId.fullid, options.loader)
    val loadPath = loadOpts.flatMap( x => x.path )
    modelCache.getOrElse(fullId.fullid)({
      val v = options.loader match {
        case "utf8" => utf8Loader.loadModel(fullId.fullid, loadPath.getOrElse(options.modelIdToPath(fullId.id)), options ).asInstanceOf[assetCreator.MODEL]
        case _ => assetCreator.loadModel(fullId.fullid, loadPath.getOrElse(options.modelIdToPath(fullId.id)), options )
      }
      if (dataManager != null && v != null) {
        v.modelInfo = dataManager.getModelInfo(fullId.fullid).getOrElse(null)
      }
      v
    })
  }

  /** Load a model and create a new scene */
  def loadModelAsScene(modelId: String, transform: Transform = Transform(),
                       listener: LoadProgressListener[assetCreator.SCENE] = null): assetCreator.SCENE = {
    var progress: LoadProgress[assetCreator.SCENE] = null
    var loadScene3DListener: LoadProgressListener[assetCreator.SCENE]  = null
    if (listener != null) {
      progress = new LoadProgress(modelId, "loadScene3D", 1, true)
      loadScene3DListener = new PartLoadProgressListener[assetCreator.SCENE, assetCreator.SCENE](progress, listener, 0)
    }
    val fullId = FullId(modelId)
    val options = loadOptions(fullId.source)
    val loadOpts = dataManager.getModelLoadOptions(fullId.fullid, options.loader)
    val fullIdStr = FullId(modelId).fullid
    val mi = new SceneObject(0, fullIdStr, transform = transform)
    val modelInfo = dataManager.getModelInfo(fullIdStr).getOrElse(null)
    val scene = Scene(IndexedSeq(mi))
    if (modelInfo != null) {
      scene.front = loadOpts.flatMap( x => x.front ).getOrElse(modelInfo.front)
      scene.up = loadOpts.flatMap( x => x.up ).getOrElse(modelInfo.up)
      scene.unit = loadOpts.flatMap( x => x.unit ).getOrElse(modelInfo.unit)
      scene.sceneId = if (modelInfo.fullId != null) modelInfo.fullId else modelId
      scene.sceneName = modelInfo.name
      scene.category = modelInfo.category
    } else {
      scene.sceneId = modelId
    }
    if (FullId(modelId).source == "yobi3d") {
      scene.up = Vector3f.UNIT_Y
      scene.front = Vector3f.UNIT_X
    }
    val res = loadScene(scene,false,loadScene3DListener)
    if (listener != null) {
      progress.done(res)
      listener.onProgress(progress)
      listener.onDone(progress.result)
    }
    res
  }

  /** Load a scene without the full geometry */
  def estimateWssSceneScale(scene: Scene): Double = {
    // Find the room in the scene
    val roomObj = scene.objects.find( obj => obj.modelInfo.isRoom || obj.modelInfo.hasCategory("Courtyard").getOrElse(true) )
    if (roomObj.isDefined) {
      val roomScale = roomObj.get.transform.getScale()
      roomObj.get.modelInfo.unit / roomScale
    } else {
      Constants.WSS_SCENE_SCALE
    }
  }

  /** Load scene geometry for given scene */
  def loadScene(scene: Scene, useSupportHierarchy: Boolean,
                listener: LoadProgressListener[assetCreator.SCENE]): assetCreator.SCENE = {
    var progress: LoadProgress[assetCreator.SCENE] = null
    if (listener != null) {
      progress = new LoadProgress(scene.sceneId, "Loading models for scene", scene.objects.length, false)
      listener.onProgress(progress)
    }

    try {
      val gscene = if (useSupportHierarchy)
        loadSceneWithSupportHierarchy(scene, listener)(progress)
      else loadSceneWithoutSupportHierarchy(scene, listener)(progress)

      assetCreator.finalizeScene(gscene)
      if (listener != null) {
        progress.done(gscene)
        listener.onProgress(progress)
        listener.onDone(progress.result)
      }
      scene.geometricScene = gscene
      gscene
    } catch {
      case ex:Exception => {
        // Error!!!!
        logger.warn("Error loading scene " + scene.sceneId, ex)
        if (listener != null) {
          listener.onDone(null)
        }
      }
      return null
    }
  }

  private def loadModelInstance(i: Int, mi: SceneObject): assetCreator.MODEL_INSTANCE = {
    val model = loadModel(mi.modelID)
    if (model != null) {
      val modelInst = assetCreator.createModelInstance(i.toString, i, mi.transform)
      modelInst.model = model
      modelInst.nodeSelf = assetCreator.cloneNode(model.node)
      if (Constants.useSemanticCoordFront) {
        // Let's have the modelInst.node be the semantic coordinate frame of the model
        // We want to retain the current world transform for modelInst.nodeSelf
        val toSemanticCoordFrame = JmeUtils.getSemanticCoordinateFrameMatrix4f(model.up, model.front)
        val fromSemanticCoordFrame = toSemanticCoordFrame.invert(null)
        val modelMatrix4f = jme3.arrayToMatrix( mi.transform.toFloatArray() )
        val ms = modelMatrix4f.mult( fromSemanticCoordFrame )
        assetCreator.setTransform( modelInst.node, Transform(jme3.matrixToArray(ms)) )
        assetCreator.setTransform( modelInst.nodeSelf, Transform(jme3.matrixToArray(toSemanticCoordFrame)) )
      } else {
        // Don't scale or transform nodeSelf
        assetCreator.setTransform( modelInst.nodeSelf, Transform() )
      }
      assetCreator.attachChild(modelInst.node, modelInst.nodeSelf)
      assetCreator.userData.set(modelInst.node, UserDataConstants.MODEL_INDEX, i)
      assetCreator.userData.set(modelInst.node, UserDataConstants.MODEL, mi)
      modelInst
    } else {
      logger.warn("Error loading model " + mi.modelID)
      null.asInstanceOf[assetCreator.MODEL_INSTANCE]
    }
  }

  private def loadSceneWithoutSupportHierarchy(scene: Scene,
                                               listener: LoadProgressListener[assetCreator.SCENE])(progress: LoadProgress[assetCreator.SCENE] = null): assetCreator.SCENE = {
    // Convert to scene and make appropriate transforms
    val s = assetCreator.createScene(scene.sceneId)
    val modelInstances = new mutable.ArrayBuffer[assetCreator.MODEL_INSTANCE]()
    s.scene = scene
    for ((mi,i) <- scene.objects.zipWithIndex) {
      val modelInst = loadModelInstance(i, mi)
      if (modelInst != null) {
        assetCreator.attachChild(s.node, modelInst.node)
      }
      modelInstances += modelInst
      // TODO: Some of these are failed to load....
      if (listener != null) {
        progress.incLoaded()
        listener.onProgress(progress)
      }
    }
    s.modelInstances = modelInstances
    s
  }

  private def loadSceneWithSupportHierarchy(scene: Scene, listener: LoadProgressListener[assetCreator.SCENE])(progress: LoadProgress[assetCreator.SCENE] = null): assetCreator.SCENE = {
    // Convert to scene and make appropriate transforms
    val s = assetCreator.createScene(scene.sceneId)
    s.scene = scene
    val modelInstances = new mutable.ArrayBuffer[assetCreator.MODEL_INSTANCE]()
    val transforms = new ArrayBuffer[assetCreator.TRANSFORM]()
    val roots = new ArrayBuffer[Int]()
    for ((mi,i) <- scene.objects.zipWithIndex) {
      val modelInst: assetCreator.MODEL_INSTANCE = try {
        loadModelInstance(i, mi)
      } catch {
        case ex:Exception => {
          // Error!!!!
          logger.warn("Error loading modelinstance " + mi, ex)
          if (listener != null) {
            listener.onDone(null)
          }
        }
        return null
      }
      if (modelInst != null) {
        val transform = assetCreator.getLocalTransform(modelInst.node)
        transforms += transform
        assetCreator.attachChild(s.node, modelInst.node)
      } else {
        transforms += null.asInstanceOf[assetCreator.TRANSFORM]
      }
      modelInstances += modelInst
      // TODO: Some of these are failed to load....
      if (listener != null) {
        progress.incLoaded()
        listener.onProgress(progress)
      }

      if (mi.supportParentIndex >= 0) {
        val parent: SceneObject = scene.objects(mi.supportParentIndex)
        parent.addChildIndex(i)
      } else {
        roots.append( i )
      }
    }

    // Try to put model as child of parent
    var todo = new mutable.Queue[Int]()
    todo ++= roots
    while (todo.length > 0) {
      val i = todo.dequeue()
      val mi = scene.objects(i)
      val modelInst = modelInstances(i)
      if (modelInst != null) {
        val node = modelInst.node
        val childIndices = mi.childIndices
        if (childIndices != null && childIndices.size > 0) {
          val minv = assetCreator.getInverse( transforms(i) )
          for (ci <- childIndices) {
            val child = modelInstances(ci)
            if (child != null) {
              assetCreator.attachChild( node, child.node )
              // Fix child transform to be relative to parent
              // cwm = pwm * cm
              // cm = pwm^(-1)*cwm
              assetCreator.applyTransform( child.node, minv )
              // Add child to todo queue
              todo.enqueue(ci)
            }
          }
        }
      }
    }
    s.modelInstances = modelInstances
    s
  }

  def loadJson(path: String): Object = {
    try {
      WebUtils.loadJson(path).getOrElse(null)
    } catch {
      case ex: Exception =>
        logger.warn("Error reading json from " + path, ex)
        null
    }
  }

  def loadBytes(path: String, basedir: String = null): Array[Byte] = {
    val fullPath = if (path.startsWith("http:") || path.startsWith("https:")) path
      else Option(basedir).getOrElse("") + "/" + path
    logger.debug("load bytes " + fullPath)
    try {
      WebUtils.loadBytes(fullPath).getOrElse(null)
    } catch {
      case ex: Exception =>
        logger.warn("Error reading bytes from " + path, ex)
        null
    }
  }
}

object AssetLoader {
  object LoadFormat extends Enumeration {
    val OBJ_FORMAT, UTF8_FORMAT, KMZ_FORMAT = Value
    def shortName(format: LoadFormat.Value): String = {
      format match {
        case OBJ_FORMAT => "obj"
        case UTF8_FORMAT => "utf8"
        case KMZ_FORMAT => "kmz"
      }
    }
    def apply(f: String): LoadFormat.Value = {
      f match {
        case "obj" => OBJ_FORMAT
        case "utf8" => UTF8_FORMAT
        case "kmz" => KMZ_FORMAT
        case _ => withName(f)
      }
    }
  }

  def getSceneFileBase(fullId: FullId, dir: String, usePerSceneDir: Boolean = false): String = {
    val separator = if (IOUtils.isWebFile(dir)) "/" else File.separator
    if (usePerSceneDir)
      dir + fullId.source + separator + fullId.id + separator + fullId.id
    else dir + fullId.source + separator + fullId.id
  }

  case class LoadOptions (
                     name: String = "",
                     geometryPath: Option[String] = None,
                     materialsPath: Option[String] = None,
                     loadMaterials: Boolean = true,
                     doubleSided: Boolean = false,
                     normalizeRGB: Boolean = false,
                     ignoreZeroRGBs: Boolean = false,
                     invertTransparency: Boolean = false,
                     supportShadow: Boolean = Constants.useShadows,
                     defaultColor: Array[Double] = null,
                     loader: String = null,
                     compressionExt: String = null,
                     modelIdToPath: String => String = null
                     )

  // TODO: Refactor into more generic progress data structures
  object LoadStatus extends Enumeration {
    type LoadStatus = Value
    val LOADING, DONE = Value
  }

  case class LoadProgress[T >: Null](
                               name: String,
                               stage: String,   // Load stage
                               total: Int = 1,  // Total to load
                               trackParts: Boolean = false
                            ) {
    var status: LoadStatus.Value = LoadStatus.LOADING
    var loaded: Int = 0 // Loaded
    var result: T = null
    val partsProgress: Array[LoadProgress[_]] = // Load progress for each part
      if (trackParts) Array.ofDim[LoadProgress[_]](total) else null
    private var partsPercents: Array[Float] = null // How much each part contributes to the total
    def this(name: String, stage: String, result: T) {
      this(name, stage)
      this.loaded = total
      this.result = result
      this.status = LoadStatus.DONE
    }
    def setPartPercents(weights: Array[Float]) = {
      if (weights.length != total) throw new IllegalArgumentException("Invalid number of weights")
      if (!weights.forall( x => x > 0)) throw new IllegalArgumentException("Weights must be positive")
      val sum = weights.sum
      partsPercents = weights.map( x => x/sum)
    }
    def percentDone: Float = {
      if (this.status == LoadStatus.DONE) 1.0f
      else if (partsPercents == null) {
        if (partsProgress == null) {
          this.loaded.toFloat / this.total
        } else {
          this.partsProgress.map( x => if (x != null) x.percentDone else 0 ).sum / this.total
        }
      } else {
        if (partsProgress == null) {
          partsPercents.take(this.loaded).sum
        } else {
          this.partsProgress.map( x => if (x != null) x.percentDone else 0.0f )
              .zip(partsPercents).map( p => p._1 * p._2 ).sum
        }
      }
    }
    def done(result: T) {
      this.synchronized {
        this.result = result
        this.status = LoadStatus.DONE
      }
    }
    def incLoaded() {
      this.synchronized {
        this.loaded = this.loaded + 1
      }
    }
  }

  trait LoadProgressListener[T >: Null] {
    def onProgress(progress: LoadProgress[T])
    def onDone(result: T)
  }

  class PartLoadProgressListener[T >: Null,C >: Null](val parent: LoadProgress[T],
                                                      val parentListener: LoadProgressListener[T],
                                                      val childIndex: Int) extends LoadProgressListener[C] {
    def onProgress(progress: LoadProgress[C]) {
      if (parent.partsProgress != null) parent.partsProgress(childIndex) = progress
      parentListener.onProgress(parent)
    }
    def onDone(result: C) {
      parent.incLoaded()
      parentListener.onProgress(parent)
    }
  }
}

trait AssetCreator {
  type SPATIAL
  type NODE <: SPATIAL // Internal node in the scene graph
  type MESH <: SPATIAL // Leaf node
  type BBOX // Bounding box
  //  type GEOMETRY
  type MATERIAL  // Material
  type TRANSFORM

  type MODEL = Model[NODE]
  type SCENE = GeometricScene[NODE]
  type MODEL_INSTANCE = ModelInstance[NODE]

  def createScene(name: String): SCENE
  def finalizeScene(scene: SCENE) {}

  def loadModel(name: String, path: String, options: AssetLoader.LoadOptions = null): MODEL
  def createModel(name: String): MODEL
  def finalizeModel(model: MODEL, options: AssetLoader.LoadOptions = null) {}

  def createModelInstance(name: String, index: Int): MODEL_INSTANCE = createModelInstance(name, index, null.asInstanceOf[TRANSFORM])
  def createModelInstance(name: String, index: Int, transform: Transform): MODEL_INSTANCE
  = createModelInstance(name, index, arrayToTransform( transform.toFloatArray() ))
  def createModelInstance(name: String, index: Int, transform: TRANSFORM): MODEL_INSTANCE
  = new ModelInstance(createNode(name, transform), index)
  def cloneModelInstance(mi: MODEL_INSTANCE, index: Int, skipChildren: Boolean = false): MODEL_INSTANCE = {
    val cloned = new ModelInstance(cloneNode(mi.node, skipChildren), index)
    cloned.model = mi.model
    cloned
  }

  def createNode(name: String): NODE = createNode(name, null.asInstanceOf[TRANSFORM])
  def createNode(name: String, transform: Transform): NODE
    = createNode(name, arrayToTransform( transform.toFloatArray() ))
  def createNode(name: String, transform: TRANSFORM): NODE
  def createMaterial(mi: MaterialInfo): MATERIAL
  def createMesh(params: Map[String,Any]): MESH
  def attachChild(node: NODE, child: NODE): NODE
  def attachMesh(node: NODE, child: MESH): NODE
  def cloneNode(node: NODE, skipChildren: Boolean = false): NODE

  def arrayToTransform(ta: Array[Float]): TRANSFORM
  def transformToArray(t: TRANSFORM): Array[Float]

  // If node has local transform tx, then after applying transform , the local transform is now t*x
  def applyTransform(node: NODE, t: TRANSFORM): NODE
  def setTransform(node: NODE, t: TRANSFORM): NODE
  def getLocalTransform(node: NODE): TRANSFORM
  def getWorldTransform(node: NODE): TRANSFORM
  def getInverse(t: TRANSFORM): TRANSFORM

  def applyTransform(node: NODE, t: Transform): NODE =
    applyTransform(node, arrayToTransform( t.toFloatArray() ))
  def setTransform(node: NODE, t: Transform): NODE =
    setTransform(node, arrayToTransform( t.toFloatArray() ))
//  def getInverse(t: Transform): Transform =
//    Transform(transformToArray( getInverse( arrayToTransform(t.toFloatArray()) ) ))

  val userData = new UserData()
}


