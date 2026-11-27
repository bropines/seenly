// A stand-in for OpenCV's dnn module, which this build leaves out: WeChat's decoder runs without
// its two networks (the detector and the super-resolution model), and never reaches these unless
// it is given model files, which Seenly never does.
#pragma once

#include <string>
#include "opencv2/core.hpp"

namespace cv {
namespace dnn {

class Net {
public:
    void setInput(InputArray, const std::string& = "", double = 1.0, const Scalar& = Scalar()) { unavailable(); }
    Mat forward(const std::string& = "") { unavailable(); return Mat(); }
    bool empty() const { return true; }

private:
    [[noreturn]] static void unavailable() { CV_Error(Error::StsNotImplemented, "built without dnn"); }
};

inline Net readNetFromCaffe(const std::string&, const std::string& = "") {
    CV_Error(Error::StsNotImplemented, "built without dnn");
}

inline void blobFromImage(InputArray, OutputArray, double = 1.0, const Size& = Size(),
                          const Scalar& = Scalar(), bool = false, bool = false, int = CV_32F) {
    CV_Error(Error::StsNotImplemented, "built without dnn");
}

}  // namespace dnn
}  // namespace cv
